import java.nio.ByteBuffer;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Map;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;


/**
 * Associated to a connection id and to a fellow endpoint (host, port). It handles messages from different streams and
 * dispatches them accordingly. It uses two threads, a Sender and a Handler, and two blocking queues, one for received messages 
 * and one for sent messages. They work in a producer-consumer fashion, where the producer is the thread calling method 
 * send() or receive(), and the consumer is the thread Sender or Handler.
 * 
 * Methods:
 * public ConnectedLayer(String desthost, int destport, long connectionid);
 * public void send(byte[] payload, int streamid); 		//Very important: you have to specify your stream id in order to send!
 * public void receive(byte[] payload, String host);
 * public Integer accept();
 * public void register(StreamLayer sl, Integer id);
 * public void deliverTo(Layer above);
 * public void close();
 * 
 */
public class ConnectedLayer implements Layer {
	//Constants
	private final static int QUEUECAPACITY = 1000;		//Capacity for all BlockingQueues
	private final static int SENDERWAITINGTIME = 500;
	private final static int TIMEBEFORECLOSING = 1000;
	
	//Obvious variables
	private String desthost;							
	private int destport;
	private long connectionid;
	
	//Threads
	private Handler handler;
	private Sender sender;
	private Timer timer;
	private boolean inContact;
	
	//Queues
	private BlockingQueue<Frame> receivequeue;
	private BlockingQueue<Message> sendingqueue;
	
	//State of the connection (mainly useful for closing state)
	private int state; //0 when open, 1 when closing or closed
	
	//Maps Stream ids to their corresponding stream layers
	private Map<Integer,StreamLayer> above;
	
	//Structures to handle pending streams: whenever a message with an unknown stream id is received, it is stored in a queue, 
	//waiting for someone to call the accept() method
	//When the accept() method has been called, the streamid is not in the queue anymore, but we still should remember which one it
	//is until the register() method is called. This is the purpose of the Set
	private BlockingQueue<Integer> toAcceptStreams;
	private Set<Integer> pendingStreams;
	
	//For each packet sent and not acked yet, contains a set of tuples (streamid, byteoffset), enabling us to signal the corresponding
	//StreamLayer which handles flow control
	private Map<Integer,Set<long[]>> framesInPacket;	
	
	
	
	class Handler extends Thread{
		
		public Handler(){
		}
		
		public void run(){
			boolean cont = true;
			
			while(cont){
				try{
					Frame frame = receivequeue.take();
					
					/***  HANDLE STREAM FRAME *****/
					if (frame.type == Frame.FRAME_STREAM){
						synchronized (above){
							if (above.containsKey(frame.streamid)){
								above.get(frame.streamid).receive(frame);
							}
							else{
								if (pendingStreams.contains(frame.streamid))		//if this stream is waiting for a dedicated layer to be built
									receivequeue.put(frame);						//just put the frame back into the queue
								else{
									toAcceptStreams.put(frame.streamid);			//if not, put the stream into the waiting queue so that a StreamLayer above can be built
									pendingStreams.add(frame.streamid);			//register it in the set of pending streams
									receivequeue.put(frame);						//and put the frame back into the queue as well
								}
							}
						}
					}
					
					/**** HANDLE ACK *****/
					if (frame.type == Frame.FRAME_ACK){
						synchronized(framesInPacket){
							Set<long[]> framesacked = framesInPacket.get(frame.largestacked);  //Look which frames were in the corresponding frame
							if (framesacked != null){
								for (long[] tup : framesacked){
									StreamLayer l = above.get((int)tup[0]);
									if (l==null)
										System.out.println("ERROR l null");
									l.receiveACK(tup[1]);			//And notify the corresponding stream layer
								}
								framesInPacket.remove(frame.largestacked);		//Remove this entry from framesInPacket
							}
						}
					}
				} catch (InterruptedException e){
					cont = false;
				}
			}
		}
	}
	
	class Sender extends Thread{
		private int upPacketNum;
		private static final int capacity = 1024;
		
		public Sender(){
			this.upPacketNum = 0;
		}
		
		public void run(){
			while(true){
				try{
					LinkedList<byte[]> toSend = new LinkedList<byte[]>();
					Set<long[]> frames = new HashSet<long[]>();
					int size = 1+4+8;
					
					/** Wait for some more frames to come **/
					while (size < capacity){
						Message mess = sendingqueue.poll(SENDERWAITINGTIME, TimeUnit.MILLISECONDS);
						//If time has expired, send packet
						if (mess == null)
							break;
						byte[] payload = mess.payload;
						
						//Remember which frames you put in the packet, so that acks can work
						if (mess.streamid != -1){
							long[] tup = new long[2];
							tup[0] = mess.streamid;
							tup[1] = mess.offset;
							frames.add(tup);
						}
						size += payload.length;
						toSend.add(payload);
					}
					if (size == 13)  //If no packet to send in this round...
						continue;
					
					//Finally, build the packet
					byte[] data = new byte[size];
					ByteBuffer bb = ByteBuffer.wrap(data);
					bb.put((byte)8); //Flags
					bb.putLong(connectionid);
					bb.putInt(this.upPacketNum);
					while(!toSend.isEmpty()){
						bb.put(toSend.removeFirst());
					}
					
					//Remember frames sent
					synchronized(framesInPacket){
						if (!frames.isEmpty())
							framesInPacket.put(this.upPacketNum, frames);
					}
					this.upPacketNum++;
					
					GroundLayer.send(data, desthost, destport);
				} catch (InterruptedException e){
					break;
				}
			}
		}
	}
	
	/**
	 * Mere constructor
	 */
	public ConnectedLayer(String desthost, int destport, long connectionid){
		this.desthost = desthost;
		this.destport = destport;
		this.connectionid = connectionid;
		this.state = 1;
		this.receivequeue = new ArrayBlockingQueue<Frame>(QUEUECAPACITY);
		this.sendingqueue = new ArrayBlockingQueue<Message>(QUEUECAPACITY);
		this.above = new HashMap<Integer,StreamLayer>();
		this.toAcceptStreams = new ArrayBlockingQueue<Integer>(QUEUECAPACITY);
		this.pendingStreams = new HashSet<Integer>();
		this.framesInPacket = new HashMap<Integer,Set<long[]>>();
		this.inContact = true;
		this.handler = new Handler();
		this.sender = new Sender();
		this.handler.start();
		this.sender.start();
		
		TimerTask task = new TimerTask(){
			
			public void run() {
				if (inContact)
					inContact = false;
				else
					close();
			}
			} ;
		timer = new Timer() ;
		timer.scheduleAtFixedRate(task , 30000, 30000) ;
	}
	
	/*
	 * This is the send function that should be used. Simply adds messages to the queue
	 */
	public void send(byte[] payload, int streamid, long offset){
		if (state == 0)
			return;
		Message mess = new Message();
		mess.payload = payload;
		mess.streamid = streamid;
		mess.offset = offset;
		try{
			sendingqueue.put(mess);
		} catch (InterruptedException e){}
	}
	
	/*
	 * This function should not be used
	 */
	public void send(byte[] payload){
		/*try {
			this.sendingqueue.put(payload);
		} catch (InterruptedException e) {
		}*/
	}
	
	/*
	 * Receiving method. Process the packet, and adds frames to the receivequeue for further processing, and interaction with the layers
	 * above.
	 */
	public void receive(byte[] payload, String host){ 
		if (this.state == 0)
			return;
		
		inContact = true;
		//Enforcing host migration capability
		if (!this.desthost.equals(host.split(":")[0].substring(1)))
			this.desthost = host.split(":")[0].substring(1);
			
		ByteBuffer bb = ByteBuffer.wrap(payload);
        boolean DIVERSIFICATION_NONCE=false;
        boolean SEND_ACK = false;
        boolean CLOSE = false;
        Date date = new Date();
        long t = date.getTime();
		
		byte flags = bb.get(); 
	    if(isSet(flags,2))
	        DIVERSIFICATION_NONCE=true;
	    if(isSet(flags,4))
	       return;
	    if(isSet(flags,4)&&isSet(flags,5))
	        return;											//Packet number sizes not supported
	    
		bb.getLong();											//ConnectionId (ignore)
		if(DIVERSIFICATION_NONCE){
            for (int i=0; i<8; i++)
            	bb.getInt();
        }
		int packetnum = bb.getInt();							//Packet Number (default to 32 bits)
		while(bb.hasRemaining()){
			try{
				byte frametype = bb.get();
				Frame frame = new Frame();
				
				/**** STREAM FRAME ****/
				if ((frametype & ((byte)0b10000000)) == (byte)0b10000000){ 
					SEND_ACK = true;
					frame.type = Frame.FRAME_STREAM;
					frame.isFin = ((frametype & ((byte)0b01000000)) == (byte)0b01000000);
					frame.hasDataLength = ((frametype & ((byte)0b00100000)) == (byte)0b00100000);
					frame.streamid = bb.getInt();
					frame.offset = bb.getLong();
					frame.datalength = bb.getShort();
					frame.data = new byte[frame.datalength];
					for (int i=0; i<frame.datalength; i++)
						frame.data[i] = bb.get();
					this.receivequeue.put(frame);
				}
				
				/****** ACK FRAME *****/
				else if ((frametype & ((byte)0b01000000)) == (byte)0b01000000){
					frame.type = Frame.FRAME_ACK;
					frame.largestacked = bb.getInt();
					this.receivequeue.put(frame);
				}
				//PING
				else if (frametype == 7){
					SEND_ACK = true;
				}
				//STOP_WAITING
				else if (frametype == 6){
					SEND_ACK = true;
				}
				//BLOCKED
				else if (frametype == 5){
					SEND_ACK = true;
				}
				//WINDOW_UPDATE
				else if (frametype == 4){
					SEND_ACK = true;
				}
				//GOAWAY
				else if (frametype == 3){
					SEND_ACK = true;
				}
				//CONNECTION_CLOSE
				else if (frametype == 2){
					System.out.println("Connection close received");
					SEND_ACK = true;
					CLOSE = true;
				}
				//RST_STREAM
				else if (frametype == 1){}
				//PADDING
				else if (frametype == 0){}   
				
				/***** SENDING ACK *****/
				if (SEND_ACK){
					byte[] ackpayload = new byte[1+4+2];
					ByteBuffer ackbb = ByteBuffer.wrap(ackpayload);
					ackbb.put((byte)0b01001010);
					ackbb.putInt(packetnum);
					ackbb.putShort((short)(date.getTime()-t));	//ACK delay
					Message ack = new Message();
					ack.payload = ackpayload;
					ack.streamid = -1;
					this.sendingqueue.put(ack);
				}
				
				if (CLOSE){
					this.close();
				}
			} catch (InterruptedException e){
				//Nothing
			}
		}
	}
	
	boolean isSet(byte value, int bit){
		return (value&(1<<bit))!=0;
	}
	
	public Integer accept() throws InterruptedException{
		try{
			return this.toAcceptStreams.take();
		} catch (InterruptedException e){
			throw e;
		}
	}
	
	public void register(StreamLayer sl, Integer id){
		synchronized(this.above){
			this.above.put(id, sl);
			if (this.pendingStreams.contains(id))
				this.pendingStreams.remove(id);
		}
	}
	
	public void unregister(Integer id){
		synchronized(this.above){
			this.above.remove(id);
		}
	}
	
	
	public void deliverTo(Layer above){
		throw new UnsupportedOperationException("ConnectedLayer does not support only one layer above");
	}
	

	
	public void close(){
		System.out.println("Closing Connection Layer");
		if (this.state == 0)
			return;
		
		
		/** Send Connection_close **/
		byte[] payld = new byte[1];
		payld[0] = (byte)2;
		this.send(payld,-1,0);
		this.state = 0;
		this.timer.cancel();
		
		/** Wait till all messages have been sent **/
		try{
			Thread.sleep(TIMEBEFORECLOSING);
		} catch(InterruptedException e){
		}
		
		/** End all threads, close layers above **/
		this.sender.interrupt();
		this.handler.interrupt();
		synchronized(this.above){
			for (StreamLayer l : this.above.values()){
				l.close();
			}
		}
	}
}
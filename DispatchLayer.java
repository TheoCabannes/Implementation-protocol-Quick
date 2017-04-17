import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * DispatchLayer dispatches messages received from GroundLayer according to their connection ids.
 * It runs one thread Handler, that handles messages received thanks to a blockingqueue, where the producer is the thread calling
 * method receive() and the consumer is Handler.
 * 
 * Methods:
 * public static void start();
 * public static boolean register(Layer above, long connectionid); //Register new layer in the map id->layer
 * public static PendingConnection accept(); 		//Block the current thread until a pending connection appears, and return it
 * public DispatchLayer();  //Constructor, DO NOT USE, use start() instead
 * public void receive(byte[] payload, String source);
 * public void send(byte[] payload);
 * public void close();		//DO NOT USE, use end() instead
 * public static void end();
 * 
 */

public class DispatchLayer implements Layer{
	private static DispatchLayer dispatcher = null;					//The only instance of DispatchLayer.
	private final static int RECEIVEQUEUECAPACITY = 1000;
	private BlockingQueue<Message> receivequeue;					//BlockingQueue for handling received messages.
	private BlockingQueue<PendingConnection> toAcceptqueue;			//BlockingQueue for pending connections, that one can retrieve via accept method.
	private Map<Long,Layer> above;									//Maps connection ids to ConnectedLayers
	private Set<Long> pendingConnections;							//List of pending connection ids, to track the connections that are waiting to be accepted, and those that have been accepted and for which the layer is being built.
	private Handler handler;										//The thread that will handle messages received.
	
	/**
	 * This thread handles messages received by the layer and waiting in the queue
	 */
	private class Handler extends Thread{
		private BlockingQueue<Message> receivequeue;
		private BlockingQueue<PendingConnection> toAcceptqueue;
		private Set<Long> pendingConnections;
		private Map<Long,Layer> above;
		
		public Handler(BlockingQueue<Message> receivequeue, BlockingQueue<PendingConnection> toAcceptQueue, Map<Long,Layer> above, Set<Long> pendingConnections){
			this.receivequeue = receivequeue;
			this.above = above;
			this.toAcceptqueue = toAcceptQueue;
			this.pendingConnections = pendingConnections;
		}
		
		boolean isSet(byte value, int bit){
			return (value&(1<<bit))!=0;
		}
		
		public void run(){
			boolean cont = true;
			Message mess;
			while (cont){
				try{
					mess = receivequeue.take();
					ByteBuffer bb = ByteBuffer.wrap(mess.payload);
			        boolean PUBLIC_RESET=false;
			        boolean CONNECTION_ID=false;
			        long connectionid = 0;
					byte flags = bb.get();  								//Flags
					if(isSet(flags,1))
				       PUBLIC_RESET=true;
				    if(isSet(flags,3))
				        CONNECTION_ID=true;
				    
				    if(PUBLIC_RESET)										//Ignore public reset messages						
				          continue;	
				    if(CONNECTION_ID){
			            connectionid = bb.getLong();
			        } else{
			        	continue;											//Ignore packets which don't come with connection id
			        }
					synchronized (this.above){  //above serves as a lock for both structures pendingConnections and above.
						if (this.above.containsKey(connectionid)){
							this.above.get(connectionid).receive(mess.payload, mess.host);
						} else {
							if (pendingConnections.contains(connectionid)) //If the layer above is being built,
								receivequeue.put(mess);			//just put that message back into the queue
							else{								//Otherwise, add to the queue of pending connections, and to the set
								pendingConnections.add(connectionid);						
								this.toAcceptqueue.put(new PendingConnection(mess.payload,mess.host,connectionid));
							}
						}
					}
					
				} catch (InterruptedException e){
					cont = false;
				}  
			}
		}
	}
	
	public static void start(){
		if (dispatcher == null)
			dispatcher = new DispatchLayer();
		GroundLayer.deliverTo(dispatcher);
	}
	
	public static boolean register(Layer above, long connectionid){
		if (dispatcher == null)
			return false;
		synchronized (dispatcher.above){		//above serves as a monitor for both pendingConnections and above.
			if (dispatcher.above.containsKey(connectionid))
				return false;
			dispatcher.above.put(connectionid, above);						//add the connection to the map
			if (dispatcher.pendingConnections.contains(connectionid)){		//and if it was a pending connection, now it is not!
				dispatcher.pendingConnections.remove(connectionid);
			}
			return true;
		}
	}
	
	public static PendingConnection accept() throws InterruptedException{
		if (dispatcher == null)
			return null;
		try{
			return dispatcher.toAcceptqueue.take();				//Simply returns element from the queue of pending connections
		} catch (InterruptedException e){
			throw e;
		}
	}
	
	public DispatchLayer(){
		this.receivequeue = new ArrayBlockingQueue<Message>(RECEIVEQUEUECAPACITY);
		this.toAcceptqueue = new ArrayBlockingQueue<PendingConnection>(RECEIVEQUEUECAPACITY);
		this.pendingConnections = new HashSet<Long>();
		this.above = new HashMap<Long,Layer>();
		this.handler = new DispatchLayer.Handler(this.receivequeue,this.toAcceptqueue, this.above, this.pendingConnections);
		this.handler.start();
	}

	
	public void receive(byte[] payload, String source){
		try {
			receivequeue.put(new Message(payload,source));
		} catch (InterruptedException e) {
			System.err.println("Interrupted Exception: message could not be received");
		}
	}
	
	public void send(byte[] payload){
		System.err.println("Do not use DispatchLayer to send");
	}

	public void deliverTo(Layer above) {
		System.err.println("DispatchLayer doesn't support a single Layer above");
	}

	public void close() {
		synchronized(this.above){
			for(Layer l : this.above.values()){
				l.close();
			}
		}
		this.handler.interrupt();
	}
	
	public static void end() {
		dispatcher.close();
	}
	
}
// import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;


public class StreamLayer {
	
//__________________________________STATE IDs_____________________________
	final static int IDLE = 0 ;
	final static int RESERVED = 1 ;
	final static int OPEN = 2 ;
	final static int HC_REMOTE = 3 ;
	final static int HC_LOCAL = 4 ;
	final static int CLOSED = 5 ;
	
	final static long WINDOW_START = 12 ;
	final static long TIME_INTERVAL = 2000 ;
	
	private HashMap<Long, Frame> slidingWindow  = new HashMap<Long,Frame>() ;
	private static final int QUEUECAPACITY = 1000;
	private ConnectedLayer sublayer;
	private Handler handler;
	private Timer timer ;
	private BlockingQueue<Frame> receivequeue;
	private Layer above;
	private long upoffset;
	private long downoffset ;
	private long receiveoffset ;
	private long window ;
	
	private int id;
	private int state ;
	
	final private Object send_lock = new Object() ;
	final private Object window_lock = new Object() ;
	
	Frame lastFrame;
	
	class Handler extends Thread{
		private BlockingQueue<Frame> receivequeue;
		private Layer above;
		public Handler(BlockingQueue<Frame> receivequeue){
			this.receivequeue = receivequeue;
		}
		
		public void run(){
			boolean cont = true;
			try{
				while(this.above == null)
					sleep(1000);
			} catch(InterruptedException e){
				cont = false;
			}
			while (cont){
				try{
					Frame f = receivequeue.take();
					if (f.offset == receiveoffset){
						handleRecv(f)	;	
						receiveoffset += f.datalength;
					}else {
						receivequeue.put(f);
					}
					} catch(InterruptedException e){
					cont = false;
				}
			}
		}
	}
	
	
	public StreamLayer(ConnectedLayer sublayer, int id){
		this.sublayer = sublayer;
		this.id = id;
		this.upoffset = 0;
		this.downoffset = 0 ;
		this.receiveoffset = 0;
		this.window = WINDOW_START ;
		this.receivequeue = new ArrayBlockingQueue<Frame>(QUEUECAPACITY);
		this.handler = new Handler(this.receivequeue);
		this.handler.start();
		this.state = IDLE ;
		
		TimerTask task = new TimerTask(){
			
			public void run() {
				synchronized(window_lock)
				{
				//if(state == OPEN)
					for(Frame f : slidingWindow.values())
						{
							System.out.println("Resending");
							sublayer.send(f.toByte(), id, f.offset);
						}
				}
			}
			} ;
		timer = new Timer() ;
		timer.scheduleAtFixedRate(task , 100, TIME_INTERVAL) ;

	}

	public void receive(Frame frame){
		this.lastFrame = frame;
		
			try{
				this.receivequeue.put(frame);
			} catch (InterruptedException e){}
		
	}
	
	public void handleRecv(Frame frame) 
	{

//____________________________TYPE CHECK__________________________________________________ 
		 
		 
//________________________________________________________________________________________
//__________________________________________STREAM FRAME__________________________________
//________________________________________________________________________________________
		 
		 if (frame.type == Frame.FRAME_STREAM)		//STREAM DATA FRAME
		 {
			 //System.out.println("Current state = " + this.state) ;
			 
			 if((this.state == IDLE)||(this.state == RESERVED))
			 {
				 this.state = OPEN ;
			 }
			 
			 if(this.state == HC_REMOTE)
			 {
				 //this.state = CLOSED ;
				 //close() ;
				 //sendRST(0, Frame.QUIC_STREAM_DATA_AFTER_TERMINATION) ; 
				 return ;
			 }
			 
			 if(this.state == CLOSED)
			 {
				 return ;
			 }
			


			 if((!frame.hasDataLength)&&(!frame.isFin))
			 {
				 System.err.println("Error : no dataLength and not Fin") ;
				 return ;
			 }

			 
//___________________________________________DATA SENDING__________________________________
			 	
			 above.receive(frame.data, null); 
			 
//___________________________________________CLOSING IF NEEDED_____________________________
			 if(frame.isFin) //isFIN
				 {
				 	if((this.state == OPEN))
				 	{
				 		this.state = HC_REMOTE ;
				 	}
				 	
				 	if(this.state == HC_LOCAL)
				 	{
				 		this.state = CLOSED ;
				 		//close() ;
				 	}
				 }
		 }
//_________________________________________________________________________________________		 
//__________________________________________WINDOW UPDATE FRAME____________________________
//_________________________________________________________________________________________
		 else if (frame.type == Frame.WINDOW_UPDATE)
		 {
			 if(this.state == IDLE)
			 {
				 System.err.println("ERR : BAD FRAME RECEIVED IN STATE "+ this.state ) ;
				 return ;
			 }

			 updateFrame(frame.offset) ;
			 return ;
		 }
//_________________________________________________________________________________________		 
//__________________________________________BLOCKED FRAME__________________________________
//_________________________________________________________________________________________		
		 else if(frame.type == Frame.BLOCKED)
		 {
			 System.err.println("----FLOW BLOCKED FOR STREAM : "+id+"----") ;
			 return ;
		 }
//_________________________________________________________________________________________		 
//__________________________________________RST_STREAM FRAME_______________________________
//_________________________________________________________________________________________		 
		 else if(frame.type == Frame.RST_STREAM)
		 {
			 handleRstStream(frame.offset,frame.errcode ) ;
			 return ;
		 }
	}
	
	/* private void sendRST(long i, int error) {
		byte[] mess = new byte[1+4+8+4] ;
		ByteBuffer bb = ByteBuffer.wrap(mess) ;
		bb.put((byte)Frame.RST_STREAM) ;
		bb.putInt(this.id) ;
		bb.putLong(i) ;
		bb.putInt(error);
		sublayer.send(mess,this.id,this.upoffset);
	} */

	private void updateFrame(long offset) {
		this.window = offset ;
	}

	private void handleRstStream(long offset, int errcode) {
		this.state = CLOSED;
	}
//_____________________________________SEND_________________________________________________
	
	public void send(byte[] payload, boolean isFin){
		//System.out.println("Current state:"+state);
		if ((state==CLOSED)||(state==HC_LOCAL)){
			System.err.println("State error: cannot send");
			return;
		}
		if ((state==IDLE)||(state==RESERVED))
			this.state = OPEN;
		synchronized(send_lock)
		{
			while(this.upoffset - this.downoffset > this.window)
				try {
					send_lock.wait();
				} catch (InterruptedException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			
			Frame f = new Frame(Frame.FRAME_STREAM,isFin,!isFin, this.id, this.upoffset, (short)payload.length, payload) ;
			
			if(isFin)
				this.state = HC_LOCAL ;
			
			this.upoffset += payload.length;
			
		synchronized(window_lock)
		{
			slidingWindow.put(f.offset,f);
		}
		
			sublayer.send(f.toByte(), this.id, f.offset);
		}
		
	}
	
	public void deliverTo(Layer above){
		this.above = above;
		this.handler.above = above;
	}
	
	public void receiveACK(long offset)
	{
		synchronized(window_lock)
		{
			if(slidingWindow.containsKey(offset)){
				slidingWindow.remove(offset) ;
				//System.out.println("Frame removed");
			}
			
			if(offset == this.downoffset)		
			{
				if(slidingWindow.isEmpty())
					this.downoffset = this.upoffset ;
				else
				{
					long min = Long.MAX_VALUE ;
					for(Long l :slidingWindow.keySet())
					{
						if(min > l )
							min = l ;
					}
					this.downoffset = min ;
				}
			}
		}	
		synchronized (send_lock){
			if(this.upoffset-this.downoffset < this.window)
				send_lock.notifyAll();
		}
		
	}
	
	public void close(){
		timer.cancel();
		this.handler.interrupt();
	}
}

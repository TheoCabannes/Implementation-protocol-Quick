import java.io.FileWriter;
import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

class FileReceiver implements Layer {
  private final static int QUEUECAPACITY = 1000;
  private final StreamLayer subLayer;
  private BlockingQueue<byte[]> queue;
  public FileReceiverHandler handler;
  
  class FileReceiverHandler extends Thread{
	  private BlockingQueue<byte[]> queue;
	  private FileWriter fw;
	  
	  public FileReceiverHandler(BlockingQueue<byte[]> queue){
		  this.queue = queue;
		  this.fw = null;
	  }
	  
	  public void run(){
		  boolean cont = true;
		  byte[] payload;
		  while(cont){
			  try{
				  payload = this.queue.take();
				  
				  String message = new String(payload); 
				  try{
					  if (message.startsWith("SEND")){
						  String filename = message.substring(5);
						  this.fw = new FileWriter("_received_"+filename);
						  System.out.println("Ready to receive "+filename);
					  }
					  else if (message.equals("**CLOSE**")){
						  cont = false;
					  }
					  else if (message.equals("FILE NOT FOUND")){
						  System.err.println("File not found on distant server");
						  cont = false;
					  }
					  else if (this.fw != null){
						  fw.write(message+"\n");
						  System.out.println(message);
					  }
				  } catch (IOException e){
					  System.err.println("Error while writing to file "+e.getMessage());
				  }
			  } catch (InterruptedException e){
				  cont = false;
			  }
		  }
		  System.out.println("Closing Filehandler.");
	  }
  }

  public FileReceiver(ConnectedLayer cl, String filename) {
	this.queue = new ArrayBlockingQueue<byte[]>(QUEUECAPACITY);
	this.handler = new FileReceiverHandler(this.queue);
	this.handler.start();
	int id = (int)(Math.random() * Integer.MAX_VALUE);
    subLayer = new StreamLayer(cl,id);
    subLayer.deliverTo(this);
    cl.register(subLayer, id);
    subLayer.send(("GET "+filename).getBytes(), true);
  }

  public void send(byte[] payload) {
    throw new UnsupportedOperationException(
        "don't support any send from above");
  }

  public synchronized void receive(byte[] payload, String sender) {
	  try {
		this.queue.put(payload);
	} catch (InterruptedException e) {
	}
  }

  public void deliverTo(Layer above) {
    throw new UnsupportedOperationException("don't support any Layer above");
  }

  public void close() {
	  //NOTHING
  }
}

public class Client {

	public static void main(String[] args) {
		if (args.length != 1) {
		      System.err.println("syntax : java Client myPort");
		      return;
		}
		
		String host1 = "127.0.0.1";
		int port1 = 5001;
		String file11 = "taka.txt";
		String file12 = "abc.txt";
		String host2 = "127.0.0.1";
		int port2 = 5002;
		String file21 = "taka.txt";
		String file22 = "abc.txt";
		
		if (GroundLayer.start(Integer.parseInt(args[0]))){
			DispatchLayer.start();
			
			long c = (long)(Math.random() * Long.MAX_VALUE);  
			ConnectedLayer connection1 = new ConnectedLayer(host1,port1,c);
			if (!DispatchLayer.register(connection1, c)){
				System.err.println("Couldn't register connection 1 in DispatchLayer");
				DispatchLayer.end();
				return;
			}
			
			c = (long)(Math.random() * Long.MAX_VALUE);  
			ConnectedLayer connection2 = new ConnectedLayer(host2,port2,c);
			if (!DispatchLayer.register(connection2, c)){
				System.err.println("Couldn't register connection 2 in DispatchLayer");
				DispatchLayer.end();
				return;
			}
			
			FileReceiver fr11 = new FileReceiver(connection1, file11);
			FileReceiver fr12 = new FileReceiver(connection1, file12);
			FileReceiver fr21 = new FileReceiver(connection2, file21);
			FileReceiver fr22 = new FileReceiver(connection2, file22);
			
			try{
				fr11.handler.join();
				fr12.handler.join();
				fr21.handler.join();
				fr22.handler.join();
			} catch (InterruptedException e){
			}
			
			DispatchLayer.end();
			GroundLayer.close();
		}
	}
}

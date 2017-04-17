import java.io.File;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

class FileSender implements Layer{
	private FileSenderThread sender;
	private StreamLayer sublayer;
	
	class FileSenderThread extends Thread{
		private String filename;
		private StreamLayer sublayer;
		
		public FileSenderThread(String filename, StreamLayer sublayer) {
			this.filename = filename;
			this.sublayer = sublayer;
		}
		
		public void run(){
			try{
				sublayer.send(("SEND "+filename).getBytes(), false);
				Scanner sc = new Scanner(new File(filename));
				while (sc.hasNextLine()){
					sublayer.send(sc.nextLine().getBytes(), false);
				}
				sublayer.send("**CLOSE**".getBytes(), true);
				sc.close();
			} catch(FileNotFoundException e){
				System.err.println(e);
				sublayer.send("FILE NOT FOUND".getBytes(), true);
				return;
			}
		}
	}
	
	public void receive(byte[] payload, String host){
		if (sender == null){
			String message = new String(payload); 
			if (message.startsWith("GET")){
				String filename = message.substring(4);
				this.sender = new FileSenderThread(filename, this.sublayer);
				this.sender.start();
			}
		}
	}
	
	public FileSender(StreamLayer sublayer){
		this.sender = null;
		this.sublayer = sublayer;
	}
	
	public void send(byte[] payload){
		throw new UnsupportedOperationException("don't use to send");
	}
	
	public void deliverTo(Layer above){
		throw new UnsupportedOperationException("don't support any Layer above");
	}
	
	public void close(){
		//NOTHING
	}
}

class NewStreamsHandler extends Thread{
	ConnectedLayer cl;
	
	public NewStreamsHandler(ConnectedLayer cl){
		this.cl = cl;
	}
	
	public void run(){
		while (true){
			try{
				Integer pendingstream = cl.accept();
				if (pendingstream != null){
					StreamLayer sl = new StreamLayer(cl,pendingstream);
					FileSender fs = new FileSender(sl);
					sl.deliverTo(fs);
					cl.register(sl,pendingstream);
				}
			} catch (InterruptedException e){
				break;
			}
		}
	}
}

public class Server {

	public static void main(String[] args) {
		if (args.length != 1) {
		      System.err.println("syntax : java Server myPort");
		      return;
		}
		
		if (GroundLayer.start(Integer.parseInt(args[0]))){
			DispatchLayer.start();
			List<NewStreamsHandler> handlers = new ArrayList<NewStreamsHandler>();
			
			while (true){
				try{
					PendingConnection pc = DispatchLayer.accept();
					if (pc != null){
						String[] hostaddress = pc.host.split(":");	//Separate IP address from port num
						String host = hostaddress[0].substring(1);
						int port = Integer.parseInt(hostaddress[1]);
						ConnectedLayer cl = new ConnectedLayer(host, port, pc.id);
						NewStreamsHandler nsh = new NewStreamsHandler(cl);
						nsh.start();
						handlers.add(nsh);
						cl.receive(pc.payload, pc.host);
						DispatchLayer.register(cl, pc.id);
					}
				} catch (InterruptedException e){
					break; 
				}
			}
		}
	}

}

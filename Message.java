/*
 * Convenient wrapper class.
 */

public class Message {
	public byte[] payload;
	public String host;	//Destination/Source
	public int streamid;
	public long offset;
	
	public Message(){}
	
	public Message(byte[] payload, String peer) {
		this.payload = payload;
		this.host = peer;
	}
	
	
}

/*
 * Wrapper class
 */

public class PendingConnection {
	public byte[] payload;
	public String host;
	public long id;
	
	public PendingConnection(byte[] payload, String host, long id) {
		this.payload = payload;
		this.host = host;
		this.id = id;
	}
	
}

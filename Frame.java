import java.nio.ByteBuffer;

public class Frame {
	public static final int FRAME_STREAM = 0x0;
	public static final int RST_STREAM = 0x1 ;
	public static final int FRAME_ACK = 2;
	public static final int WINDOW_UPDATE = 0x4;
	public static final int BLOCKED = 0x5 ;
	
	public static final int QUIC_STREAM_DATA_AFTER_TERMINATION = 0x2 ;

	
	public int type;
	public int streamid;
	public long offset;
	public short datalength;
	public byte[] data;
	public int largestacked;
	public int errcode ;
	public boolean isFin;
	public boolean hasDataLength;

	
	public Frame() {
		
		this.type = 0;
		this.streamid = 0;
		this.offset = 0;
		this.datalength = 0;
		this.data = null;
		this.largestacked = 0;
		this.errcode = 0 ;
		this.hasDataLength = false ;
		this.isFin = false ;
	}
	
	public Frame(int type, boolean isFin, boolean hasDataLength, int streamid, long offset, short dataLength, byte[] data )
	{
		this.type = type ;
		this.isFin = isFin ;
		this.streamid = streamid ;
		this.offset = offset ;
		this.datalength = dataLength ;
		this.data = data ;
	}
	
	public byte[] toByte()
	{
		byte[] mess = null ;
		
		if(this.type == FRAME_STREAM)
		{
			mess = new byte[data.length+2+8+4+1];
			ByteBuffer bb = ByteBuffer.wrap(mess);
			if(!isFin)
				bb.put((byte)0b10100000);
			else
				bb.put((byte)0b11000000);
			
			bb.putInt(this.streamid);
			bb.putLong(this.offset);
			//this.upoffset += payload.length;
			bb.putShort((short)data.length);
			bb.put(data);
		}
		return mess ;
	}
	
	
}

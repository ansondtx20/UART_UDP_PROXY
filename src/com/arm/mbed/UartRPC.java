package com.arm.mbed;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

public class UartRPC {
	public static final String      TAG = "PROXY";
	private static final String		_DELIMITER = "\\|";
	private static final String		_HEAD = "[";
	private static final String		_TAIL = "]";
	
	// RPC functions supported:
	private static final int 		SOCKET_OPEN_FN  = 0x01;
	private static final int 	    SOCKET_CLOSE_FN = 0x02;
	private static final int 	    SEND_DATA_FN    = 0x04;
	private static final int 	    RECV_DATA_FN    = 0x08;
	private static final int 	    GET_LOCATION_FN = 0x16;
	
	// UDP Socket Config
	private static final int	    SOCKET_TIMEOUT_MS = 10000;			// 10 seconds
	private static final int		SOCKET_BUFFER_SIZE = 2048;			// socket buffer size
	
	private InetAddress 			m_address = null;
	private int    					m_port = 0;
	
	private UartRPCCallbacks        m_handler = null;
	private DatagramSocket 			m_socket = null;
	private boolean					m_connected = false;
	private Runnable 				m_listener = null;
	private Thread				    m_listener_thread = null;
	private boolean					m_do_run_listener = false;
	
	private String					m_args = "";
	private String					m_accumulator = "";
	private int						m_fn_id = 0;
	
	private boolean					m_send_status = false;
	private DatagramPacket			m_send_packet = null;
	private MyLocation			    m_location = null;
				
	public UartRPC(UartRPCCallbacks handler,Context context) {
		this(context);
		this.setCallbackHandler(handler);
	}
	
	public UartRPC(Context context) {
		this.reset();
		this.m_handler = null;
		this.m_location = new MyLocation(context);
		this.m_location.updateLocation();
	}
	
	public void setCallbackHandler(UartRPCCallbacks handler) {
		this.m_handler = handler;
	}
	
	private void stopListener() {
		if (this.m_listener_thread  != null) {
			try {
				this.m_do_run_listener = false;
				this.m_listener_thread.join((SOCKET_TIMEOUT_MS+1000));
				this.m_listener_thread = null;
				this.m_listener = null;
			}
			catch (Exception ex) {
				Log.w(TAG, "stopListener(): exception caught during listener thread stop(): " + ex.getMessage());
			}
		}
	}
	
	private void reset() {
		this.m_args = "";
		this.m_accumulator = "";
		this.m_fn_id = 0;
	}
	
	public boolean accumulate(String data) {
		boolean do_dispatch = false;
		
		if (data != null && data.length() > 0) {
			// accumulate...
			this.m_accumulator += data;
			
			// see if we have everything...
			if (this.m_accumulator.contains(_HEAD) && this.m_accumulator.contains(_TAIL)) {
				// ready to dispatch
				Log.d(TAG, "accumulate(): packet ready for dispatch...");
				do_dispatch = true;
			}
			else {
				// continue accumulating
				Log.d(TAG, "accumulate(): continue accumulating...");
			}
		}
		else if (data != null) {
			Log.w(TAG, "accumulate(): data length is 0... ignoring...");
		}
		else {
			Log.w(TAG, "accumulate(): data is NULL... ignoring...");
		}
		
		return do_dispatch;
	}
	
	public String getAccumulation() { return this.m_accumulator; }; 
	public boolean dispatch() { return this.dispatch(this.parse(this.m_accumulator)); }
	
	private String[] parse(String data) {
		// remove HEAD and TAIL
		String tmp1 = data.replace(_HEAD,"");
		String tmp2 = tmp1.replace(_TAIL,"");
		
		// split by delimiter now...
		return tmp2.split(_DELIMITER);
	}
	
	private boolean dispatch(String rpc_call[]) {
		boolean success = false;
		
		// slot 0 is the RPC command fn id, slot 1 is the RPC args...
		try {
			this.m_fn_id = Integer.parseInt(rpc_call[0]);
			this.m_args = "";
			if (rpc_call.length > 1) this.m_args = rpc_call[1];
			Log.d(TAG,"dispatch(): fn_id=" + this.m_fn_id + " args: [" + this.m_args + "]");
			
			// dispatch to appropriate function for processing
			switch (this.m_fn_id) {
				case SOCKET_OPEN_FN: 
					success = this.rpc_socket_open(this.m_args);
					this.m_handler.ackSocketOpen(success);
					this.m_connected = success;
					break;
				case GET_LOCATION_FN:
					success = this.rpc_get_location(this.m_args);
					break;
				case SOCKET_CLOSE_FN:
					success = this.rpc_socket_close(this.m_args);
					if (success == true && this.m_connected == true) this.m_connected = false;
					break;
				case SEND_DATA_FN:
					success = this.rpc_send_data(this.m_args);
					break;
				default:
					Log.w(TAG,"dispatch(): IMPROPER fn_id=" + this.m_fn_id + " args: [" + this.m_args + "]... ignoring...");
					break;
			}
		}
		catch (Exception ex) {
			Log.e(TAG,"dispatch(): Exception in dispatch(): " + ex.getMessage());
			ex.printStackTrace();
		}
		
		// reset if successful...
		this.reset();
		
		// return our status
		return success;
	}
	
	private DatagramSocket createSocket() {
		DatagramSocket socket = null;
		try {
			socket = new DatagramSocket(this.m_port);
			socket.setSoTimeout(SOCKET_TIMEOUT_MS);
			socket.setReceiveBufferSize(SOCKET_BUFFER_SIZE);
			socket.setSendBufferSize(SOCKET_BUFFER_SIZE);
			socket.setBroadcast(false);
			socket.setReuseAddress(true);
		}
		catch (Exception ex) {
			Log.e(TAG,"createSocket(): Socket Creation failed! " + ex.getMessage());
		}
		return socket;
	}
	
	private boolean connectSocket() {
		try {
			Log.d(TAG, "connectSocket(): opening UDP Send Socket: " + this.m_address + "@" + this.m_port);
			this.m_socket.connect(this.m_address, this.m_port);
			this.m_connected = true;
			Log.d(TAG, "connectSocket(): opening UDP Send Socket CONNECTED to: " + this.m_address + "@" + this.m_port);
		}
		catch (Exception ex) {
			this.m_connected = false;
			Log.e(TAG, "connectSocket(): UDP Socket connect FAILED: " + this.m_address + "@" + this.m_port);
		}
		return this.m_connected;
	}
	
	// RPC: open_socket()
	private boolean rpc_socket_open(String data) {
		try {
			String args[] = data.split(" ");
			
			// parse args
			this.m_address = InetAddress.getByName(args[0].trim());
			this.m_port = Integer.parseInt(args[1]);
			
			// open the socket... 
			Log.d(TAG, "rpc_open_socket(): opening UDP Send Socket: " + args[0].trim() + "@" + this.m_port);
			this.m_socket = this.createSocket();
			if (this.m_socket == null) {
				Log.e(TAG,"rpc_open_socket(): socket creation failed.. unable to connect");
				this.rpc_socket_close(null);
				return false;
			}
			
			Log.d(TAG,"rpc_open_socket(): connecting to: " + args[0].trim() + "@" + this.m_port);
			if (this.connectSocket()) {
				Log.d(TAG, "rpc_open_socket(): creating the listeners...");
				this.createListener();
				return true;
			}
			else {
				Log.e(TAG,"rpc_socket_open(): connect to: " + args[0].trim() + "@" + this.m_port + " FAILED");
				this.rpc_socket_close(null);
			}
		}
		catch(Exception ex) {
			Log.e(TAG, "rpc_open_socket(): openSocket() failed: " + ex.getMessage() + "... closing...");
			this.rpc_socket_close(null);
		}
		
		return false;
	}
	
	// RPC: get_rpc_location()
	private boolean rpc_get_location(String args) {
		boolean status = false;	
		Log.d(TAG,"rpc_get_location(): in rpc_get_location...");
		try {
			// get the current location
			String location_payload = this.m_location.getLocation();
			
			Log.d(TAG,"rpc_get_location(): location: " + location_payload);
			
			// encode
			byte data[] = location_payload.getBytes();
			String encoded_data = this.encode(data,data.length);
			
			// packet
			String packet = "[" + GET_LOCATION_FN + "|" + encoded_data + "]";
			
			// send the current location
			Log.d(TAG,"rpc_get_location(): sending current location packet: " + packet);
			
			// split and send
			status = this.m_handler.splitAndSendData(packet);
			if (status == true)
				Log.d(TAG,"rpc_get_location(): location sent successfully");
			else 
				Log.e(TAG,"rpc_get_location(): location send FAILED");
		}
		catch (Exception ex) {
			Log.d(TAG,"rpc_get_location(): sendOverUART failed: " + ex.getMessage());
		}
		
		return status;	
	}
	
	public void close() { this.rpc_socket_close(null); }
	
	// RPC: close_socket()
	private boolean rpc_socket_close(String args) {
		Log.d(TAG, "close(): stopping listener...");
		this.stopListener();
		Log.d(TAG, "close(): closing socket...");
		this.closeSocket();
		Log.d(TAG, "close(): resetting to default...");
		this.reset();
		Log.d(TAG, "close(): completed.");
		return true;
	}
		
    private void closeSocket() {
    	if (this.m_socket != null) {
    		Log.d(TAG, "closeSocket(): closing socket...");
			m_socket.close();
    	}
    }
	
	private void createListener() {
		this.m_do_run_listener = true;
		if (this.m_listener == null) { 
			this.m_listener = new Runnable() {
				@Override
				public synchronized void run() {
					while (m_do_run_listener) {
						Log.d(TAG, "listener(): waiting on receive()...");
						try {
							byte[] receiveData = new byte[SOCKET_BUFFER_SIZE];
							DatagramPacket p = new DatagramPacket(receiveData,receiveData.length);
							if (m_socket != null) {
								m_socket.receive(p);
								Log.d(TAG, "listener(): received data... processing...");
								byte[] data = p.getData();
								int data_length = p.getLength();
								if (data != null && data.length > 0) {
									Log.d(TAG, "listener(): data length: " + data_length + " (data.length=" + data_length + ") ... sending over UART...");
									m_handler.sendOverUART(data,data_length);
									Log.d(TAG, "listener(): send over UART completed");
								}
							}
							else {
								Log.w(TAG, "listener(): socket not initialized yet (OK)");
							}
							
						}
						catch (java.net.SocketTimeoutException ex) {
							Log.w(TAG, "listener(): timed out... retrying receive...");
			        	}
						catch (IOException ex) {
							Log.e(TAG, "listener(): IO exception during receive(): " + ex.getMessage());
						}
						catch (NullPointerException ex) {
							Log.e(TAG, "listener(): Null Pointer exception during receive(): " + ex.getMessage());
							ex.printStackTrace();
						}
					}
					Log.d(TAG, "listener(): exiting listener loop...");
				}
			};
			
			try {
				this.m_listener_thread = new Thread(this.m_listener);
				this.m_listener_thread.start();
			}
			catch (Exception ex) {
				Log.e(TAG, "listener(): exception during thread start(): " + ex.getMessage()); 
			}
		}
	}
	
	// RPC: recv data
	public String rpc_recv_data(byte data[],int length) {
		// encode the data
		String encoded_data = this.encode(data,length);
		if (encoded_data != null) {
			// create the header and frame
			String frame = "" + RECV_DATA_FN + "|" + encoded_data;
			return _HEAD + frame.trim() + _TAIL;
		}
		return null;
	}
	
	// RPC: send data
	public boolean rpc_send_data(String data) {
		this.m_send_status = false;
		
		// make sure we have data to send... 
		if (data != null && data.length() > 0) {
			// decode out of Base64...
			byte[] raw_bytes = this.decode(data);
					
			// dispatch a thread off the main UI thread if everything is OK...
			if (this.m_connected == true && this.m_socket != null && raw_bytes != null && raw_bytes.length > 0) {
				// create a UDP datagram...
				this.m_send_packet = new DatagramPacket(raw_bytes,raw_bytes.length,this.m_address,this.m_port);
	
				// spawn a thread to handle this to get off the UI thread
				Thread thread = new Thread(new Runnable(){
				    @Override
				    public synchronized void run() {
			        	try {		    			
							Log.d(TAG,"send() sending...");
							m_socket.send(m_send_packet);
							m_send_status = true;
							Log.d(TAG, "send() successful.");
			        	}
						catch (SocketException ex) {
							Log.w(TAG, "send() failed SocketException: " + ex.getMessage());
							
							// reconnecting
							Log.d(TAG,"reconnecting.... address: " + m_address + " port: " + m_port);
							if (connectSocket()) {
								Log.d(TAG,"send(): reconnected... resending...");
								try {
									m_socket.send(m_send_packet);
									m_send_status = true;
									Log.d(TAG, "send() successful.");
								}
								catch (Exception ex2) {
									Log.w(TAG, "send() resend failed.. giving up: " + ex2.getMessage());
								}
							}
						}
			        	catch (IOException ex) {
			        		Log.e(TAG, "send() failed IOException: " + ex.getMessage());
			   			    //ex.printStackTrace();
			        	}
				   }
				});
				thread.start();
			}
			else if (this.m_connected == true && this.m_socket != null && raw_bytes != null) {
				Log.e(TAG, "send() failed: decoded data to send has zero length");
			}
			else if (this.m_connected == true && this.m_socket != null) {
				Log.e(TAG, "send() failed: decoded data to send was NULL");
			}
			else if (this.m_connected == true) {
				Log.e(TAG, "send() failed: socket handle was NULL");
			}
			else {
				//Log.e(TAG, "send() ignored: not connected (OK)... trying to reconnect...");
				this.connectSocket();
				//this.rpc_send_data(data);
			}
		}
		else if (data != null) {
			// no data to send
			Log.w(TAG, "send() input data has zero length... not sending...");
		}
		else {
			// data is NULL
			Log.w(TAG, "send() input data is NULL... not sending...");
		}
		
		return this.m_send_status;
	}
	
	public byte[] decode(String data) {
		try {
			byte[] b64_data = data.getBytes();
			return Base64.decode(b64_data, Base64.DEFAULT);
		}
		catch (Exception ex) {
			Log.e(TAG,"decode() caught exception while trying to decode: [" + data + "]. length: " + data.length() + " Message: " + ex.getMessage());
			ex.printStackTrace();
			
			Log.e(TAG,"decode() (EXCEPTION): just returning input data: [" + data + "]...");
			byte[] raw_data = data.getBytes();
			return raw_data;
		}
	}
	
	public String encode(byte data[],int length) {
		byte[] encoded = Base64.encode(data, 0, length, Base64.DEFAULT);
		try {
			return new String(encoded,"UTF-8");
		}
		catch (Exception ex) { 
			Log.e(TAG,"encode() caught exception while trying to encode " + length + " bytes. Exception: " + ex.getMessage());
			ex.printStackTrace();
		}
		return null;
	}
	
	public String trimData(String data) {
		String trimmed_data = "";
		if (data != null && data.length() > 0) {
			for(int i=0;i<data.length();++i) {
				if (data.charAt(i) != '\n') trimmed_data += data.charAt(i);
			}
		}
		return trimmed_data.trim();
	}
}

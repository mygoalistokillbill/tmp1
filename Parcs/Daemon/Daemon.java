import java.net.*;
import java.io.*;
import java.util.*;
import parcs.*;

public class Daemon
		extends Thread {

	private ServerSocket ss;
	private Socket sock;
	boolean quit = false, acc = false;
	private HashMap point_channels = new HashMap();
	private HashMap point_data = new HashMap();
	int pointsNum = 0;

	public void run() {
		try {
			ss = new ServerSocket(Const.DAEMON_TCP_PORT);
			System.out.println("Accepting connections on port " +
					Const.DAEMON_TCP_PORT + "...");

			while (!quit) {
				try {
					acc = true;
					sock = ss.accept();
					acc = false;
				}
				catch (SocketException se) {
					if (!quit) {
						System.err.println(se);
						continue;
					}
					else
						return;
				}
				if ( (sock == null) || sock.isClosed()) {
					if ( (ss == null) || ss.isClosed())
						return;
					else
						continue;
				}

				new SockThread(sock).start();
			} //while
		}
		catch (BindException e) {
			System.err.println("TCP port " + Const.DAEMON_TCP_PORT +
					" already in use.\n"
					+ "May be another copy of Daemon launched.\n"
					+ "Try to use command line option --dmport <port>");
		}
		catch (IOException e) {
			e.printStackTrace();
			return;
		}
	}

	public static void main(String[] args) {
		String taskname = Const.parseArgs(args);
		(new UDPServer()).start();
		if (taskname != null)
			(new parcs.Executor(taskname)).start();
		(new Daemon()).run();
	}

	private boolean receiveFile(String filename, DataInputStream din) {
		try {
			int len = din.readInt();
			if (len == 0)
				return true;

			FileOutputStream fout = new FileOutputStream(filename /*+(++cnt)*/);
			byte[] buf = new byte[len < 4096 ? len : 4096];
			int c;
			do {
				c = din.read(buf, 0, len > buf.length ? buf.length : len);
				if (c == -1)
					break;
				fout.write(buf, 0, c);
				len -= c;
			}
			while (len > 0);
			fout.close();
			if (len > 0)
				return false;
		}
		catch (IOException e) {
			e.printStackTrace();
			return false;
		}
		return true;
	}

	class AMExecutor
			extends Thread { //????? ?????, ? ??????? ??????????? ?????????
		private Socket sock;
		private channel chan;
		private ClassLoader loader;
		private task curTask;
		private int pointNum;

		public AMExecutor(channel chan, Socket sock, task ctask, int pnum) {
			this.chan = chan;
			this.sock = sock;
			loader = chan.loader;
			curTask = ctask;
			pointNum = pnum;
		}

		public void run() {
			AM obj = null;
			boolean recoveryAM = false;
			String classname;
			try {
				classname = (String)chan.objin.readObject();

				if (curTask.recovery != null)
					obj = curTask.recovery.ExecuteClassEvent(pointNum, classname);

					//try { obj = (AM)loader.loadClass(classname).newInstance();
				if (obj != null)
					recoveryAM = true;
				else
					synchronized (this) {
						try {
							//Class clazz = loader.loadClass(classname, true);
							Class clazz = Class.forName(classname, true, loader);
							obj = (AM)clazz.newInstance();
						}
						catch (ClassNotFoundException e) {
							System.err.println("Class "
								+ classname + " for point " + pointNum +
								" not found");
							return;
						}
					}
			}
			catch (Exception e) {
				System.err.println("Executor: Invalid stream format: " + e);
				HostInfo.closeSocket(sock);
				return;
			}

			point p = new point(sock, curTask, pointNum);
			pointsNum++;
			System.out.println("Starting class " + obj.getClass().getName() +
					" on point "
					+ curTask.number + ":" + p.number + " ...");

			Integer i = new Integer(pointNum);
			ArrayList arrl = null;
			HashMap hashm = null;
			if (point_channels.containsKey(i))
				arrl = (ArrayList)point_channels.get(i);
			if (point_data.containsKey(i))
				hashm = (HashMap)point_data.get(i);

			if (recoveryAM)
			{
/*				if (hashm == null)
					hashm = new HashMap();
				hashm.put("serv", sock);*/
			}

			chan.from = pointNum;
			chan.index = -1;

			obj.run(new AMInfo(curTask, chan, arrl, hashm));

			if (point_channels.containsKey(i)) {
				for (int j = 0; j < ((ArrayList)point_channels.get(i)).size(); j++)
					( (channel)((ArrayList)point_channels.get(i)).get(j)).close();
				((ArrayList)point_channels.get(i)).clear();
				point_channels.remove(i);
			}
			if (point_data.containsKey(i))
			{
				((HashMap)point_data.get(i)).clear();
				point_data.remove(i);
			}

			HostInfo.closeSocket(sock);
			pointsNum--;
			p.delete();
		}
	}

	class ServSockThread
			extends Thread {
		private channel chan;
		private Socket sock;
		private ServerSocket serv = null;
		ServSockThread() {
		}

		public void run() {
			try {
				serv = new ServerSocket(Const.POINT_CONNECTION_PORT);
				while (!quit) {
					try {
						sock = serv.accept();
					}
					catch (SocketException se) {
						if (!quit) {
							System.err.println(se);
							continue;
						}
						else
							return;
					}
					if ( (sock == null) || sock.isClosed()) {
						if ( (serv == null) || serv.isClosed())
							return;
						else
							continue;
					}

					BufferedInputStream inp;
					BufferedOutputStream out;
					try {
						inp = new BufferedInputStream(sock.getInputStream());
						out = new BufferedOutputStream(sock.getOutputStream());
						chan = new channel(inp, out, true);
						System.out.println("Channel created with " +
								sock.getInetAddress() /*.getHostName()*/);

						Integer i = null;
						i = new Integer(chan.din.readInt());
						if (!point_channels.containsKey(i))
							point_channels.put(i, new ArrayList());
						( (ArrayList)point_channels.get(i)).add(chan);

					}
					catch (IOException ex1) {
					}

				}

			}
			catch (IOException ex) {
			}
		}
	}

	class SockThread
			extends Thread {
		private Socket sock;
		private channel chan;
		SockThread(Socket sock) {
			this.sock = sock;
		}

		public void run() {
			BufferedInputStream inp;
			BufferedOutputStream out;
			byte type;
			task curtask = null;
			Integer i = null;
			int pointnum = 0;
			try {
				inp = new BufferedInputStream(sock.getInputStream());
				out = new BufferedOutputStream(sock.getOutputStream());
				chan = new channel(inp, out, true);
				chan.sock = sock;

				System.out.println("Channel created with " +
						sock.getInetAddress() /*.getHostName()*/);

                for (; ; ) {
					type = chan.din.readByte();

                    switch (type) {
						case Const.DM_RECEIVE_TASK: //???????? ????????? ???????? ?????
							curtask = (task)chan.objin.readObject();
							curtask = task.getUniqueTask(curtask);
							pointnum = chan.din.readInt();
							continue;
						case Const.DM_EXECUTE_CLASS: //????????? ?????? ?? ??????????
							if (curtask != null)
								(new AMExecutor(chan, sock, curtask,
										pointnum)).start();
							break;
						case Const.DM_LOAD_CLASSES: //????????? ??????
							loadClasses();
							continue;
						case Const.DM_LOAD_JARFILES:
							loadJarFiles(curtask);
							continue;
						case Const.DM_ECHO: //????, ?.?. ???-??????
							if (!echo())
								return;
							break;
						case Const.DM_PERFORMANCE: //????????? ??????????????????
							(new LinpackLauncher(chan, sock)).start();
							break;
						case Const.DM_CONNECT_POINT:
							if (curtask == null)
							{
								System.err.println(
										"Error: DM_CONNECT_POINT before DM_RECEIVE_TASK");
								continue;
							}
							InetAddress ip = (InetAddress)chan.objin.readObject();
							Object name = chan.objin.readObject();
							int near_p = chan.din.readByte();
							Object pname = chan.objin.readObject();
							int far_p = chan.din.readByte();

							if (curtask.recovery != null)
								curtask.recovery.CreateChannelEvent(near_p, far_p);

							Socket sockc = new Socket(ip, Const.DAEMON_TCP_PORT); //POINT_CONNECTION_PORT);

							BufferedInputStream inpc;
							BufferedOutputStream outc;
							inpc = new BufferedInputStream(sockc.getInputStream());
							outc = new BufferedOutputStream(sockc.
									getOutputStream());

							i = new Integer(near_p);
							if (!point_channels.containsKey(i))
								point_channels.put(i, new ArrayList());

							channel schan = new channel(outc, inpc,
									near_p,
									( (ArrayList)point_channels.get(i)).size(),
									curtask.recovery);
							schan.sock = sockc;
							schan.works = true;

							schan.dout.writeByte(Const.DM_CONNECT_WAIT);
							schan.objout.writeObject(pname);
							schan.dout.writeInt(far_p);
							schan.objout.writeObject(curtask.recovery);
							schan.dout.flush();

							if (!schan.din.readBoolean())
							{
								System.out.println("Error creating connection " +
														sockc.getInetAddress());
								if (!sockc.isClosed())
									sockc.close();
								sockc = null;

								chan.dout.writeBoolean((boolean)false);
								chan.dout.flush();

								continue;
							}

							( (ArrayList)point_channels.get(i)).add(schan);

							if (name != null) {
								if (!point_data.containsKey(i))
									point_data.put(i, new HashMap());
								( (HashMap)point_data.get(i)).put(name,
										schan);
							}
							chan.dout.writeBoolean((boolean)true);
							chan.dout.flush();
							continue;
						case Const.DM_CONNECT_WAIT:
							Object cname = chan.objin.readObject();
							chan.from = chan.din.readInt();
							chan.recovery = (IRecovery)chan.objin.readObject();
							i = new Integer(chan.from);
							if (!point_channels.containsKey(i))
								point_channels.put(i, new ArrayList());
							chan.index = ( (ArrayList)point_channels.get(i)).size();
							( (ArrayList)point_channels.get(i)).add(chan);

							if (cname != null) {
								if (!point_data.containsKey(i))
									point_data.put(i, new HashMap());
								( (HashMap)point_data.get(i)).put(cname, chan);
							}
							chan.dout.writeBoolean((boolean)true);
							chan.dout.flush();
							break;
						case Const.DM_ADD_POINT_DATA:
							if (curtask == null)
							{
								System.err.println(
										"Error: DM_ADD_POINT_DATA before DM_RECEIVE_TASK");
								continue;
							}
							i = (Integer)chan.objin.readObject();
							Object key = chan.objin.readObject();
							Object value = chan.objin.readObject();

							if (curtask.recovery != null)
								curtask.recovery.AddUserDataEvent(i.intValue(), key, value);

							if (!point_data.containsKey(i))
								point_data.put(i, new HashMap());
							( (HashMap)point_data.get(i)).put(key, value);

							continue;
						default:
							System.err.println(
									"Error: Unidentified command received");
							HostInfo.closeSocket(sock);
					}
					break;
				} //for
			}
			catch (Exception e) {
				e.printStackTrace();
			}
		}

		private boolean loadJarFiles(task curtask) {
			try {
				int filesnum = chan.din.readInt();
				for (int i = 0; i < filesnum; i++) {
					String filename = (String)chan.objin.readObject();
					if (curtask.addJarFile(filename)) {
						chan.dout.writeBoolean(true);
						chan.out.flush();
						if (!receiveFile(filename, chan.din))
							return false;
						System.out.println("Received file " + filename);
					}
					else {
						chan.dout.writeBoolean(false);
						chan.out.flush();
					}
				}
				chan.loader = curtask.loader;
			}
			catch (Exception e) {
				e.printStackTrace();
				return false;
			}
			return true;
		}

		private boolean loadClasses() {
			try {
				int classnum = chan.din.readInt();
				chan.loader = new StreamClassLoader(chan.in);
				for (int i = 0; i < classnum; i++) {
					String filename = (String)chan.objin.readObject();
					chan.dout.writeBoolean(true);
					chan.out.flush();
					chan.loader.loadClass(filename);
					//Class.forName(filename, true, loader);
				}
			}
			catch (Exception e) {
				e.printStackTrace();
				return false;
			}
			return true;
		}

		private boolean echo() {
			byte[] buf = new byte[3000];
			int c = 0;
			int i = 0, total = 0;
			try {
				do {
					c = chan.in.read(buf);
					if (c < 0)
						break;
					total += c;
					chan.out.write(buf, 0, c);
					chan.out.flush();
//				System.out.println("total=" + total);
				}
				while (true);
			}
			catch (IOException e) {
				System.err.println("Echo : " + e);
				return false;
			}
			finally {
				HostInfo.closeSocket(sock);
			}
			return true;
		}

	}

}

class LinpackLauncher
		extends Thread {
	private Socket sock;
	private channel chan;

	public LinpackLauncher(channel chan, Socket sock) {
		this.chan = chan;
		this.sock = sock;
	}

	public void run() {
		System.out.println("Testing host performance ...");
		Linpack l = new Linpack();
		l.run_benchmark(7000);
		l.printResult();
		try {
			chan.dout.writeDouble(l.mflops_result);
			chan.out.flush();
		}
		catch (IOException e) {
			System.err.println("LinpackLauncher: " + e);
		}

		HostInfo.closeSocket(sock);
	}
}
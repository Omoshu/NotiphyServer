package com.frostphyr.notiphy;

import java.io.EOFException;
import java.util.List;
import java.util.ListIterator;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;
import javax.websocket.OnClose;
import javax.websocket.OnError;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.PongMessage;
import javax.websocket.Session;
import javax.websocket.server.ServerEndpoint;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@WebListener
@ServerEndpoint(
	value = "/server",
	decoders = {EntryOperationDecoder.class}
)
public class NotiphyServer implements ServletContextListener {
	
	public static final int MAX_ENTRIES = 25;
	
	private static Logger logger = LogManager.getLogger(NotiphyServer.class);
	private static HeartbeatManager heartbeatManager = new HeartbeatManager();
	private static StringBuilder logBuilder = new StringBuilder();
	
	public static void append(String message) {
		if (logBuilder.length() + message.length() < 0) {
			logBuilder.delete(0, message.length() - (Integer.MAX_VALUE - logBuilder.length()));
		}
		logBuilder.append(message);
	}
	
	public static String getLog() {
		return logBuilder.toString();
	}
	
	public static HeartbeatManager getHeartbeatManager() {
		return heartbeatManager;
	}
	
	@Override
	public void contextInitialized(ServletContextEvent sce) {
		for (EntryType e : EntryType.values()) {
			if (!e.getClient().init(sce.getServletContext())) {
				System.exit(0);
			}
		}
	}
	
	@Override
	public void contextDestroyed(ServletContextEvent sce) {
		
	}
	
	@OnOpen
	public void onOpen(Session session) {
		heartbeatManager.start(session);
	}
	
	@OnMessage
	public void onMessage(Session session, EntryOperation[][] operations) {
		if (operations != null) {
			int count = 0;
			for (int i = 0; i < operations.length; i++) {
				EntryCollection<?, ?> entries = EntryType.values()[i].getClient().getEntries();
				if (operations[i][EntryOperation.ADD] != null) {
					entries.addAll(session, operations[i][EntryOperation.ADD].getEntries());
				}
				if (operations[i][EntryOperation.REMOVE] != null) {
					entries.removeAll(session, operations[i][EntryOperation.REMOVE].getEntries());
				}
				count += entries.getCount(session);
			}
			
			if (count > MAX_ENTRIES) {
				for (int i = operations.length - 1; i >= 0; i--) {
					if (operations[i][EntryOperation.ADD] != null) {
						EntryCollection<?, ?> collection = EntryType.values()[i].getClient().getEntries();
						List<Entry> entries = operations[i][EntryOperation.ADD].getEntries();
						ListIterator<Entry> it = entries.listIterator(entries.size());
						while (it.hasPrevious() && count > MAX_ENTRIES) {
							if (remove(session, collection, it.previous())) {
								count--;
							}
						}
					}
				}
			}
			
			for (EntryType t : EntryType.values()) {
				t.getClient().getEntries().finishModifications();
			}
		}
	}
	
	@SuppressWarnings("unchecked")
	private <E extends Entry> boolean remove(Session session, EntryCollection<E, ?> collection, Entry entry) {
		return collection.remove(session, (E) entry);
	}
	
	@OnMessage
	public void onMessage(Session session, PongMessage message) {
		heartbeatManager.onPong(session, message);
	}
	
	@OnClose
	public void onClose(Session session) {
		heartbeatManager.stop(session);
		for (EntryType t : EntryType.values()) {
			t.getClient().getEntries().removeAll(session);
		}
	}
	
	@OnError
	public void onError(Throwable throwable) {
		if (!(throwable instanceof EOFException)) {
			logger.error(ExceptionUtils.getStackTrace(throwable));
		}
	}

}

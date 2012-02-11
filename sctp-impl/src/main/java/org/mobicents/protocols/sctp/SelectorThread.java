/*
 * JBoss, Home of Professional Open Source
 * Copyright 2011, Red Hat, Inc. and/or its affiliates, and individual
 * contributors as indicated by the @authors tag. All rights reserved.
 * See the copyright.txt in the distribution for a full listing
 * of individual contributors.
 * 
 * This copyrighted material is made available to anyone wishing to use,
 * modify, copy, or redistribute it subject to the terms and conditions
 * of the GNU General Public License, v. 2.0.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of 
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU 
 * General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License,
 * v. 2.0 along with this distribution; if not, write to the Free 
 * Software Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston,
 * MA 02110-1301, USA.
 */

package org.mobicents.protocols.sctp;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.AbstractSelectableChannel;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import javolution.util.FastList;
import javolution.util.FastMap;

import org.apache.log4j.Logger;
import org.mobicents.protocols.api.Association;
import org.mobicents.protocols.api.IpChannelType;

import com.sun.nio.sctp.AssociationChangeNotification;
import com.sun.nio.sctp.AssociationChangeNotification.AssocChangeEvent;
import com.sun.nio.sctp.SctpChannel;
import com.sun.nio.sctp.SctpServerChannel;

/**
 * @author amit bhayani
 * 
 */
public class SelectorThread implements Runnable {

	protected static final Logger logger = Logger.getLogger(SelectorThread.class);

	protected Selector selector;

	protected ManagementImpl management = null;

	protected volatile boolean started = true;

	/**
	 * @param selector
	 * @param management
	 */
	protected SelectorThread(Selector selector, ManagementImpl management) {
		super();
		this.selector = selector;
		this.management = management;
	}

	/**
	 * @param started
	 *            the started to set
	 */
	protected void setStarted(boolean started) {
		this.started = started;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.lang.Runnable#run()
	 */
	@Override
	public void run() {
		if (logger.isInfoEnabled()) {
			logger.info(String.format("SelectorThread for Management=%s started.", this.management.getName()));
		}
		while (this.started) {
			try {
				FastList<ChangeRequest> pendingChanges = this.management.getPendingChanges();

				// Process any pending changes
				synchronized (pendingChanges) {
					Iterator<ChangeRequest> changes = pendingChanges.iterator();
					while (changes.hasNext()) {
						ChangeRequest change = changes.next();
						switch (change.getType()) {
						case ChangeRequest.CHANGEOPS:
							pendingChanges.remove(change);
							SelectionKey key = change.getSocketChannel().keyFor(this.selector);
							key.interestOps(change.getOps());
							break;
						case ChangeRequest.REGISTER:
							pendingChanges.remove(change);
							SelectionKey key1 = change.getSocketChannel().register(this.selector, change.getOps());
							key1.attach(change.getAssociation());
							break;
						case ChangeRequest.CONNECT:
							if (change.getExecutionTime() <= System.currentTimeMillis()) {
								pendingChanges.remove(change);
								change.getAssociation().initiateConnection();
							}
							break;
						case ChangeRequest.CLOSE:
							pendingChanges.remove(change);
							change.getAssociation().close();
						}
					}// end of while
				}

				// Wait for an event one of the registered channels
				this.selector.select(500);

				// System.out.println("Done selecting " +
				// this.selector.selectedKeys().size());

				// Iterate over the set of keys for which events are available
				Iterator<SelectionKey> selectedKeys = this.selector.selectedKeys().iterator();

				while (selectedKeys.hasNext()) {
					SelectionKey key = selectedKeys.next();
					selectedKeys.remove();

					if (!key.isValid()) {
						continue;
					}

					// Check what event is available and deal with it
					if (key.isConnectable()) {
						this.finishConnection(key);
					} else if (key.isAcceptable()) {
						this.accept(key);
					} else if (key.isReadable()) {
						this.read(key);
					} else if (key.isWritable()) {
						this.write(key);
					}
				}

			} catch (Exception e) {
				logger.error("Error while selecting the ready keys", e);
			}
		}

		try {
			this.selector.close();
		} catch (IOException e) {
			logger.error(String.format("Error while closing Selector for SCTP Management=%s", this.management.getName()));
		}

		if (logger.isInfoEnabled()) {
			logger.info(String.format("SelectorThread for Management=%s stopped.", this.management.getName()));
		}
	}

	private void accept(SelectionKey key) throws IOException{
		if (key.channel() instanceof ServerSocketChannel)
			this.acceptTcp(key);
		else
			this.acceptSctp(key);
	}

	private void acceptSctp(SelectionKey key) throws IOException {

		// For an accept to be pending the channel must be a server socket
		// channel.
		SctpServerChannel serverSocketChannel = (SctpServerChannel) key.channel();

		// Accept the connection and make it non-blocking
		SctpChannel socketChannel = serverSocketChannel.accept();

		Set<SocketAddress> socAddresses = socketChannel.getRemoteAddresses();

		this.doAccept(socketChannel, socAddresses);
	}

	private void acceptTcp(SelectionKey key) throws IOException {
		
		// For an accept to be pending the channel must be a server socket
		// channel.
		ServerSocketChannel serverSocketChannel = (ServerSocketChannel) key.channel();

		// Accept the connection and make it non-blocking
		SocketChannel socketChannel = serverSocketChannel.accept();

		SocketAddress sockAdd = socketChannel.getRemoteAddress();
		Set<SocketAddress> socAddresses = new HashSet<SocketAddress>();
		socAddresses.add(sockAdd);

		this.doAccept(socketChannel, socAddresses);
	}

	private void doAccept(AbstractSelectableChannel socketChannel, Set<SocketAddress> socAddresses) throws IOException, ClosedChannelException {

		boolean provisioned = false;
		int port = 0;
		InetAddress inetAddress = null;

		for (SocketAddress sockAdd : socAddresses) {

			inetAddress = ((InetSocketAddress) sockAdd).getAddress();
			port = ((InetSocketAddress) sockAdd).getPort();

			// Iterate through all the servers and corresponding associate to
			// check if incoming connection request matches with any provisioned
			// ip:port
			FastMap<String, Association> associations = this.management.associations;

			for (FastMap.Entry<String, Association> n = associations.head(), end = associations.tail(); (n = n.getNext()) != end && !provisioned;) {
				AssociationImpl association = (AssociationImpl)n.getValue();
				// compare port and ip of remote with provisioned
				if ((port == association.getPeerPort()) && (inetAddress.getHostAddress().equals(association.getPeerAddress()))) {
					provisioned = true;

					if (!association.isStarted()) {
						logger.error(String.format("Received connect request for Association=%s but not started yet. Droping the connection! ",
								association.getName()));
						socketChannel.close();
						break;
					}

					((AssociationImpl) association).setSocketChannel(socketChannel);

					// Accept the connection and make it non-blocking
					socketChannel.configureBlocking(false);

					// Register the new SocketChannel with our Selector,
					// indicating we'd like to be notified when there's data
					// waiting to be read
					SelectionKey key1 = socketChannel.register(this.selector, SelectionKey.OP_READ);
					key1.attach(association);

					if (logger.isInfoEnabled()) {
						logger.info(String.format("Connected %s", association));
					}

					if (association.getIpChannelType() == IpChannelType.Tcp) {
						AssocChangeEvent ace = AssocChangeEvent.COMM_UP;
						AssociationChangeNotification2 acn = new AssociationChangeNotification2(ace);
						association.associationHandler.handleNotification(acn, association);
					}

					break;
				}
			}
		}// for (SocketAddress sockAdd : socAddresses)

		if (!provisioned) {
			// There is no corresponding Associate provisioned. Lets close the
			// channel here
			logger.warn(String.format("Received connect request from non provisioned %s:%d address. Closing Channel", inetAddress.getHostAddress(), port));
			socketChannel.close();

		}
	}

	private void finishConnection(SelectionKey key) throws IOException{
		AssociationImpl association = (AssociationImpl) key.attachment();
		if (association.getIpChannelType() == IpChannelType.Sctp)
			this.finishConnectionSctp(key);
		else
			this.finishConnectionTcp(key);
	}

	private void finishConnectionSctp(SelectionKey key) throws IOException {

		AssociationImpl association = (AssociationImpl) key.attachment();
		try {
			SctpChannel socketChannel = (SctpChannel) key.channel();

			if (socketChannel.isConnectionPending()) {

				// TODO Loop? Or may be sleep for while?
				while (socketChannel.isConnectionPending()) {
					socketChannel.finishConnect();
				}
			}

			if (logger.isInfoEnabled()) {
				logger.info(String.format("Asscoiation=%s connected to=%s", association.getName(), socketChannel.getRemoteAddresses()));
			}

			// Register an interest in writing on this channel
			key.interestOps(SelectionKey.OP_READ);
		} catch (Exception e) {
			logger.error(String.format("Exception while finishing connection for Association=%s", association.getName()), e);
			association.scheduleConnect();
		}
	}

	private void finishConnectionTcp(SelectionKey key) throws IOException {

		AssociationImpl association = (AssociationImpl) key.attachment();

		try {
			SocketChannel socketChannel = (SocketChannel) key.channel();
			if (socketChannel.isConnectionPending()) {

				// TODO Loop? Or may be sleep for while?
				while (socketChannel.isConnectionPending()) {
					socketChannel.finishConnect();
				}
			}

			if (logger.isInfoEnabled()) {
				logger.info(String.format("Asscoiation=%s connected to=%s", association.getName(), socketChannel.getRemoteAddress()));
			}

			// Register an interest in writing on this channel
			key.interestOps(SelectionKey.OP_READ);

			AssocChangeEvent ace = AssocChangeEvent.COMM_UP;
			AssociationChangeNotification2 acn = new AssociationChangeNotification2(ace);
			association.associationHandler.handleNotification(acn, association);

		} catch (Exception e) {
			logger.error(String.format("Exception while finishing connection for Association=%s", association.getName()), e);

			association.scheduleConnect();
		}
	}

	private void read(SelectionKey key) throws IOException {
		AssociationImpl association = (AssociationImpl) key.attachment();
		association.read();
	}

	private void write(SelectionKey key) throws IOException {
		AssociationImpl association = (AssociationImpl) key.attachment();
		association.write(key);
	}

	class AssociationChangeNotification2 extends AssociationChangeNotification {
		
		private AssocChangeEvent assocChangeEvent;

		public AssociationChangeNotification2(AssocChangeEvent assocChangeEvent) {
			this.assocChangeEvent = assocChangeEvent;
		}

		@Override
		public com.sun.nio.sctp.Association association() {
			return null;
		}

		@Override
		public AssocChangeEvent event() {
			return this.assocChangeEvent;
		}
	}
}


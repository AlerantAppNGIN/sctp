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
package org.mobicents.protocols.sctp.multiclient;

import java.io.IOException;
import java.net.SocketAddress;



import org.apache.log4j.Logger;

import com.sun.nio.sctp.Notification;
import com.sun.nio.sctp.AbstractNotificationHandler;
import com.sun.nio.sctp.AssociationChangeNotification;
import com.sun.nio.sctp.HandlerResult;
import com.sun.nio.sctp.PeerAddressChangeNotification;
import com.sun.nio.sctp.SendFailedNotification;
import com.sun.nio.sctp.ShutdownNotification;

/**
 * Implements NotificationHandler for the OneToManyAssocMultiplexer class. Its main responsibility is to delegate the notification to the NotificationHandler of the corresponding
 * OneToManyAssociationImpl. Each handler method calls the resolveAssociatonImpl method to find the corresponding Association. 
 * 
 * @author alerant appngin
 * 
 */
class MultiAssociationHandler extends AbstractNotificationHandler<OneToManyAssocMultiplexer> {

	private static final Logger logger = Logger.getLogger(MultiAssociationHandler.class);
	
	public MultiAssociationHandler() {

	}

	/**
	 * The delegateNotificationHandling method resolves the OneToManyAssociationImpl instance which belongs to the given Notification and calls the handleNotification method of the resolved Association.
	 * In case the association instance can not be resolved the method returns the value of the defaultResult parameter. 
	 * 
	 * @param not  - Notification that need to be delegated 
	 * @param defaultResult - Default return value used when Association instance can not be resolved
	 * @param multiplexer - The OneToManyAssocMultiplexer that is attached to this MultiAssociationHandler instance
	 * @return - If delegation is successful it returns the return result of the handler method of the corresponding OneToManyAssociationImpl instance otherwise ű
	 * returns the value of the defaultResult parameter.
	 */
	private HandlerResult delegateNotificationHandling(Notification not, HandlerResult defaultResult, OneToManyAssocMultiplexer multiplexer) {
		OneToManyAssociationImpl association = multiplexer.resolveAssociationImpl(not.association());		
		if (association == null) {
			return defaultResult;
		}
		if (logger.isDebugEnabled()) {
			logger.debug("Notification: "+not+" is being delagated to associationHandler: "+association.associationHandler);
		}
		return association.associationHandler.handleNotification(not, association);
	}
	
	@Override
	public HandlerResult handleNotification(AssociationChangeNotification not, OneToManyAssocMultiplexer multiplexer) {
		if (logger.isDebugEnabled()) {
			logger.debug("handleNotification(AssociationChangeNotification not, OneToManyAssocMultiplexer multiplexer) is called");
		}
		if (not.association() == null) {
			logger.error("Cannot handle AssociationChangeNotification: association method of AssociationChangeNotification: "+not+" returns null value, handler returns CONTINUE");
			return HandlerResult.CONTINUE;
		}		
		return delegateNotificationHandling(not, HandlerResult.CONTINUE, multiplexer);
	}

	@Override
	public HandlerResult handleNotification(ShutdownNotification not, OneToManyAssocMultiplexer multiplexer) {
		if (logger.isDebugEnabled()) {
			logger.debug("handleNotification(ShutdownNotification not, OneToManyAssocMultiplexer multiplexer) is called");
		}
		if (not.association() == null) {
			logger.error("Cannot handle ShutdownNotification: assoction method of ShutdownNotification: "+not+" returns null value, handler returns RETURN");
			return HandlerResult.RETURN;
		}
		return delegateNotificationHandling(not, HandlerResult.RETURN, multiplexer);
	}

	@Override
	public HandlerResult handleNotification(SendFailedNotification notification, OneToManyAssocMultiplexer multiplexer) {
		if (logger.isDebugEnabled()) {
			logger.debug("handleNotification(SendFailedNotification notification, OneToManyAssocMultiplexer multiplexer) is called");
		}
		if (notification.association() == null) {
			logger.error("Cannot handle SendFailedNotification: assoction method of SendFailedNotification: "+notification+" returns null value, handler returns RETURN");
			return HandlerResult.RETURN;
		}		
		return delegateNotificationHandling(notification, HandlerResult.RETURN, multiplexer);
	}

	@Override
	public  HandlerResult handleNotification(PeerAddressChangeNotification notification, OneToManyAssocMultiplexer multiplexer) {
		if (logger.isDebugEnabled()) {
			logger.debug("handleNotification(PeerAddressChangeNotification notification, OneToManyAssocMultiplexer multiplexer) is called");
		}
		if (notification.association() == null) {
			logger.error("Cannot handle PeerAddressChangeNotification: assoction method of PeerAddressChangeNotification: "+notification+" returns null value, handler returns CONTINUE");
			return HandlerResult.RETURN;
		}		
		return delegateNotificationHandling(notification, HandlerResult.RETURN, multiplexer);		
	}
}
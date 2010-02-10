/*
 * FrontlineSMS <http://www.frontlinesms.com>
 * Copyright 2007, 2008 kiwanja
 * 
 * This file is part of FrontlineSMS.
 * 
 * FrontlineSMS is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 * 
 * FrontlineSMS is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser
 * General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public License
 * along with FrontlineSMS. If not, see <http://www.gnu.org/licenses/>.
 */
package net.frontlinesms.smsdevice;

import java.util.*;
import java.util.concurrent.*;

import serial.*;

import net.frontlinesms.CommUtils;
import net.frontlinesms.Utils;
import net.frontlinesms.data.domain.Message;
import net.frontlinesms.listener.SmsListener;
import net.frontlinesms.smsdevice.internet.SmsInternetService;

import org.apache.log4j.Logger;
import org.smslib.CIncomingMessage;
import org.smslib.handler.CATHandler;

/**
 * SmsHandler should be run as a separate thread.
 * 
 * It handles the discovery of phones available on the system's COM ports, 
 * and also manages a pool of threads that handle the communication with as many phones as are found 
 * attached to the system.
 * 
 * Autodetection should take 30 seconds.
 * 
 * OUTGOING MESSAGES
 * When you send a new outgoing message through SmsHandler it is added to a stack of waiting messages, 
 * which it will then send to the waiting phones by turn, unless the messages are marked as being 
 * for a specific phone.
 * 
 * INCOMING MESSAGES
 * If you create SmsHandler and pass it an SmsListener, incoming messages will be reported as events 
 * to that listener. If you create the SmsHandler without the listener, the messages will just appear 
 * on the linked list of IncomingMessages, and the calling program must poll it for new messages.
 * 
 * Incoming messages are immediately removed from active phones, so if you close the program without 
 * storing the message, then you will have lost the message.
 * 
 * PHONE STATE:
 * When a phone handler is created on a port, it will attempt AT commands until it gets an OK from a modem.
 * A valid OK will make the phoneHandler set phonePresent to TRUE.
 * The PhoneHanler will then attempt to connect the full SMSLIB tools to it, to take it into 
 * connected=true state, from which you can actually send and recieve messages.
 * 
 * HTTP services:
 * In the future this will be extended to be able to interface with 
 * internet based SMS services via HTTP, to handle bulk messaging.
 * 
 * @author Ben Whitaker ben(at)masabi(dot)com
 * @author Alex Anderson alex(at)masabi(dot)com
 * 
 * FIXME need to add checks for UCS-2 support before sending UCS-2 messages
 */
public class SmsDeviceManager extends Thread implements SmsListener {
	/** List of messages queued to be sent. */
	private final ConcurrentLinkedQueue<Message> outbox = new ConcurrentLinkedQueue<Message>();
	/** List of binary messages queued to be sent. */
	private final ConcurrentLinkedQueue<Message> binOutbox = new ConcurrentLinkedQueue<Message>();
	/** List of phone handlers that this manager is currently looking after. */
	private final ConcurrentMap<String, SmsModem> phoneHandlers = new ConcurrentHashMap<String, SmsModem>();
	/** Set of SMS internet services */
	private Set<SmsInternetService> smsInternetServices = new HashSet<SmsInternetService>();

	/** Listener to be passed SMS Listener events from this */
	private SmsListener smsListener;
	/** Flag indicating that the thread should continue running. */
	private boolean running;	
	/** If set TRUE, then thread will automatically try to connect to newly-detected devices. */ 
	private boolean autoConnectToNewPhones;
	/** Signals to thread that it should search the serial ports for connected devices. */
	private boolean refreshPhoneList;

	/**
	 * Set containing all serial numbers of discovered phones.  Necessary because bluetooth/USB
	 * devices may present multiple virtual COM ports to the app. 
	 */
	private final HashSet<String> connectedSerials = new HashSet<String>();
	private String[] portIgnoreList;

	private static Logger LOG = Utils.getLogger(SmsDeviceManager.class);

	/**
	 * Create a polling-variant SMS Handler.
	 * To add a message listener, setSmsListener() should be called.
	 */
	public SmsDeviceManager() {
		super("SmsDeviceManager");

		// Load the COMM properties file, and extract the IGNORE list from
		// it - this is a list of COM ports that should be ignored.		
		this.portIgnoreList = CommProperties.getInstance().getIgnoreList();
	}

	public void setSmsListener(SmsListener smsListener) {
		this.smsListener = smsListener;
	}

	public void run() {
		LOG.trace("ENTER");
		running = true;
		while (running) {
			doRun();
		}
		LOG.trace("EXIT");
	}

	/**
	 * Run the looped behaviour from {@link #run()} once.
	 * This method is separated for simple, unthreaded unit testing.
	 */
	void doRun() {
		if (refreshPhoneList) {
			LOG.debug("Refreshing phone list...");
			// N.B. why is this not using the value from autoConnectToNewPhones? 
			listComPortsAndOwners(autoConnectToNewPhones);
			refreshPhoneList = false;
		} else {
			dispatchTextSms();
			dispatchBinarySms();
			processModemReceiving();
			// Individual phones should sleep, so there's no need to do this here!(?)  Here's
			// a token pause in case things lock up / to stop this thread eating the CPU for
			// breakfast.
			Utils.sleep_ignoreInterrupts(10);
		}
	}

	/** Handle the steps necessary when disconnecting a modem. */
	private void handleDisconnect(SmsModem modem) {
		modem.disconnect();
	}
	
	/**
	 * list com ports, optionally find phones, and optionally connect to them
	 * @param autoDiscoverPhones - if false, the call will only enumerate COM ports and find the owners - not try to auto-detect phones
	 * @param connectToAllDiscoveredPhones - only works if findPhoneNames is true, and will try to not connect to duplicate connections to the same phone.
	 */
	public void refreshPhoneList(boolean autoConnectToNewPhones) {
		this.autoConnectToNewPhones = autoConnectToNewPhones;
		refreshPhoneList = true;
	}

	/**
	 * Scan through the COM ports this computer is displaying.
	 * When an unowned port is found, we initiate a PhoneHandler
	 * detect an AT device on this port. Ignore all non-serial
	 * ports and all ports whose names' appear on our "ignore"
	 * list.
	 * @param findPhoneNames
	 * @param connectToAllDiscoveredPhones
	 */
	public void listComPortsAndOwners(boolean connectToAllDiscoveredPhones) {
		LOG.trace("ENTER");
		Enumeration<CommPortIdentifier> portIdentifiers = CommUtils.getPortIdentifiers();
		LOG.debug("Getting ports...");
		while (portIdentifiers.hasMoreElements()) {
			requestConnect(portIdentifiers.nextElement(), connectToAllDiscoveredPhones);
		}
		LOG.trace("EXIT");
	}

	/**
	 * Checks if a COM port should be ignored (rather than connected to).
	 * @param comPortName
	 * @return
	 */
	private boolean shouldIgnore(String comPortName) {
		for (String ig : portIgnoreList) {
			if (ig.equalsIgnoreCase(comPortName)) return true;
		}
		return false;
	}


	/**
	 * Request that an SMS with the specified text be sent to the requested
	 * number.
	 * @param targetNumber
	 * @param smsMessage
	 * @return the Message object 
	 */
	public void sendSMS(Message outgoingMessage) {
		LOG.trace("ENTER");
		outgoingMessage.setStatus(Message.STATUS_OUTBOX);
		if (outgoingMessage.isBinaryMessage()) {
			binOutbox.add(outgoingMessage);
			LOG.debug("Message added to binOutbox. Size is [" + binOutbox.size() + "]");
		} else {
			outbox.add(outgoingMessage);
			LOG.debug("Message added to outbox. Size is [" + outbox.size() + "]");
		}
		if (smsListener != null) smsListener.outgoingMessageEvent(null, outgoingMessage);
		LOG.trace("EXIT");
	}

	/**
	 * Remove the supplied message from outbox.
	 * @param deleted
	 */
	public void removeFromOutbox(Message deleted) {
		if(outbox.remove(deleted)) {
			if(LOG.isDebugEnabled()) LOG.debug("Message [" + deleted + "] removed from outbox. Size is [" + outbox.size() + "]");
		} else if(binOutbox.remove(deleted)) {
			if(LOG.isDebugEnabled()) LOG.debug("Message [" + deleted + "] removed from outbox. Size is [" + binOutbox.size() + "]");
		} else {
			if(LOG.isInfoEnabled()) LOG.info("Attempt to delete message found in neither outbox nor binOutbox.");
		}
	}

	/**
	 * Flags the internal thread to stop running.
	 */
	public void stopRunning() {
		this.running = false;

		// Disconnect all phones.
		for(SmsModem p : phoneHandlers.values()) {
			p.setDetecting(false);
			p.setAutoReconnect(false);
			handleDisconnect(p);
		}
		
		// Stop all SMS Internet Services
		for(SmsInternetService service : this.smsInternetServices) {
			service.stopThisThing();
		}
	}

	public void incomingMessageEvent(SmsDevice receiver, CIncomingMessage msg) {
		// If we've got a higher-level listener attached to this, pass the message 
		// up to there.  Otherwise, add it to our internal list
		if (smsListener != null) smsListener.incomingMessageEvent(receiver, msg);
	}

	public void outgoingMessageEvent(SmsDevice sender, Message msg) {
		if (smsListener != null) smsListener.outgoingMessageEvent(sender, msg);
		if (msg.getStatus() == Message.STATUS_FAILED) {
			if (msg.getRetriesRemaining() > 0) {
				msg.setRetriesRemaining(msg.getRetriesRemaining() - 1);
				msg.setSenderMsisdn("");
				sendSMS(msg);
			}
		}
	}

	public boolean hasPhoneConnected(String port) {
		SmsDevice phoneHandler = phoneHandlers.get(port);
		return phoneHandler != null && phoneHandler.isConnected();
	}

	/**
	 * called when one of the SMS devices (phones or http senders) has a change in status,
	 * such as detection, connection, disconnecting, running out of batteries, etc.
	 * see PhoneHandler.STATUS_CODE_MESSAGES[smsDeviceEventCode] to get the relevant messages
	 *  
	 * @param activeDevice
	 * @param smsDeviceEventCode
	 */
	public void smsDeviceEvent(SmsDevice device, SmsDeviceStatus deviceStatus) {
		LOG.trace("ENTER");
		
		// Special handling for modems
		if (device instanceof SmsModem) {
			LOG.debug("Event [" + deviceStatus + "]");
			SmsModem activeDevice = (SmsModem) device;
			if(deviceStatus.equals(SmsModemStatus.DISCONNECTED)) {
				// A device has just disconnected.  If we aren't using the device for sending or receiving,
				// then we should just ditch it.  However, if we *are* actively using the device, then we
				// would probably want to attempt to reconnect.  Also, if we were previously connected to 
				// this device then we should now remove its serial number from the list of connected serials.
				if(!activeDevice.isDuplicate()) connectedSerials.remove(activeDevice.getSerial());
			}
			if(deviceStatus.equals(SmsModemStatus.CONNECTING)) {
				// The max speed for this connection has been found.  If this connection
				// is a duplicate, we should set the duplicate flag to true.  Otherwise,
				// we may wish to reconnect.
				if (autoConnectToNewPhones) {
					boolean isDuplicate = !connectedSerials.add(activeDevice.getSerial());
					activeDevice.setDuplicate(isDuplicate);
					if(!isDuplicate) activeDevice.connect();
				}
			}
		}
		
		if (smsListener != null) {
			smsListener.smsDeviceEvent(device, deviceStatus);
		}
		LOG.trace("EXIT");
	}


	/**
	 * Get's all {@link SmsDevice}s that this manager is currently connected to
	 * or investigating.
	 * @return
	 */
	public Collection<SmsDevice> getAllPhones() {
		Set<SmsDevice> ret = new HashSet<SmsDevice>();
		ret.addAll(phoneHandlers.values());
		ret.addAll(smsInternetServices);
		return ret;
	}

	/**
	 * Request the phone manager to attempt a connection to a particular COM port.
	 * @param port
	 */
	public void requestConnect(String port) throws NoSuchPortException {
		requestConnect(CommPortIdentifier.getPortIdentifier(port), true);
	}

	/**
	 * <p>Attempt to connect to an {@link SmsModem} on a particular COM port.  This method allows you to specify
	 * the preffered {@link CATHandler} to be used.</p>
	 * <p>If the port is already in use then connection to the port will not be attempted.</p>
	 * @param portName
	 * @param baudRate
	 * @param preferredCATHandler
	 * @return <code>true</code> if connection is being attempted to the port; <code>false</code> if the port is already in use.
	 * @throws NoSuchPortException
	 */
	public boolean requestConnect(String portName, int baudRate, String preferredCATHandler) throws NoSuchPortException {
		CommPortIdentifier portIdentifier = CommPortIdentifier.getPortIdentifier(portName);

		if(LOG.isInfoEnabled()) LOG.info("Requested connection to port: '" + portName + "'");
		if(!portIdentifier.isCurrentlyOwned()) {
			LOG.info("Connecting to port...");
			SmsModem phoneHandler = new SmsModem(portName, this);
			phoneHandlers.put(portName, phoneHandler);
			phoneHandler.start(baudRate, preferredCATHandler);
			return true;
		} else {
			LOG.info("Port currently owned by another process: '" + portIdentifier.getCurrentOwner() + "'");
			// If we don't have a handle on this port, but it's owned by someone else,
			// then we add it to the phoneHandlers list anyway so that we can see its
			// status.
			phoneHandlers.putIfAbsent(portName, new SmsModem(portName, this));
			return false;
		}
	}

	public void addSmsInternetService(SmsInternetService smsInternetService) {
		smsInternetService.setSmsListener(smsListener);
		if (smsInternetServices.contains(smsInternetService)) {
			smsInternetService.restartThisThing();
		} else {
			smsInternetServices.add(smsInternetService);
			smsInternetService.startThisThing();
		}
	}

	/**
	 * Remove a service from this {@link SmsDeviceManager}.
	 * @param service
	 */
	public void removeSmsInternetService(SmsInternetService service) {
		smsInternetServices.remove(service);
		disconnectSmsInternetService(service);
	}
	
	public void disconnect(SmsDevice device) {
		if(device instanceof SmsModem) disconnectPhone((SmsModem)device);
		else if(device instanceof SmsInternetService) disconnectSmsInternetService((SmsInternetService)device);
	}

	private void disconnectPhone(SmsModem modem) {
		modem.setAutoReconnect(false);
		handleDisconnect(modem);
	}

	public void stopDetection(String port) {
		SmsModem smsModem = phoneHandlers.get(port);
		if(smsModem != null) {
			smsModem.setDetecting(false);
			smsModem.setAutoReconnect(false);
		}
	}

	private void disconnectSmsInternetService(SmsInternetService device) {
		device.stopThisThing();
	}

	/**
	 * Attempts to connect to the supplied comm port
	 * @param portIdentifier
	 * @param connectToDiscoveredPhone
	 * @param findPhoneName
	 */
	private void requestConnect(CommPortIdentifier portIdentifier, boolean connectToDiscoveredPhones) {
		String portName = portIdentifier.getName();
		LOG.debug("Port Name [" + portName + "]");
		if(!shouldIgnore(portName) && portIdentifier.getPortType() == CommPortIdentifier.PORT_SERIAL) {
			LOG.debug("It is a suitable port.");
			if(!portIdentifier.isCurrentlyOwned()) {
				LOG.debug("Connecting to port...");
				SmsModem phoneHandler = new SmsModem(portName, this);
				phoneHandlers.put(portName, phoneHandler);
				if(connectToDiscoveredPhones) phoneHandler.start();
			} else {
				// If we don't have a handle on this port, but it's owned by someone else,
				// then we add it to the phoneHandlers list anyway so that we can see its
				// status.
				LOG.debug("Port currently owned by another process.");
				phoneHandlers.putIfAbsent(portName, new SmsModem(portName, this));
			}
		}
	}

	public Collection<SmsInternetService> getSmsInternetServices() {
		return this.smsInternetServices;
	}

	/**
	 * Polls all {@link SmsModem}s that are set to receive messages, and processes any
	 * messages they've received.
	 */
	private void processModemReceiving() {
		Collection<SmsModem> receiveModems = getSmsModemsForReceiving();
		for(SmsModem modem : receiveModems) {
			CIncomingMessage receivedMessage;
			while((receivedMessage = modem.nextIncomingMessage()) != null) {
				incomingMessageEvent(modem, receivedMessage);
			}
		}
	}
	
	/** @return all {@link SmsModem}s that are currently connected and receiving messages. */
	private Collection<SmsModem> getSmsModemsForReceiving() {
		HashSet<SmsModem> receivers = new HashSet<SmsModem>();
		for(SmsModem modem : this.phoneHandlers.values()) {
			if(modem.isRunning() && modem.isTimedOut()) {
				// The phone's being unresponsive.  Attempt to disconnect from the phone, remove the serial
				// number from the duplicates list and then add the phone to the reconnect list so we can
				// reconnect to it later.  We should also remove the unresponsive phone from the phoneHandlers
				// list.
				if(LOG.isDebugEnabled()) LOG.debug("Watchdog from phone [" + modem.getPort() + "] has timed out! Disconnecting...");
				handleDisconnect(modem);
			} else if(modem.isConnected() && modem.isUseForReceiving()) {
				receivers.add(modem);
			}
		}
		return receivers;
	}

//> SMS DISPATCH METHODS
	/** Dispatch all messages in {@link #outbox} to suitable {@link SmsDevice}s */
	private void dispatchTextSms() {
		List<Message> messages = removeAll(this.outbox);
		dispatchSms(messages, false);
	}
	
	/** Dispatch all messages in {@link #binOutbox} to suitable {@link SmsDevice}s */
	private void dispatchBinarySms() {
		List<Message> messages = removeAll(this.binOutbox);
		dispatchSms(messages, true);
	}
	
	/**
	 * @param messages messages to dispatch
	 * @param binary <code>true</code> if the messages are binary, <code>false</code> if they are text
	 */
	private void dispatchSms(List<Message> messages, boolean binary) {
		// Try dispatching to SmsInternetServices
		List<SmsInternetService> internetServices = getSmsInternetServicesForSending(binary);
		int serviceCount = internetServices.size();
		if(serviceCount > 0) {
			// We have some SMS Internet services to send with.  These are assumed to be higher priority
			// than Sms Modems, so send all messages with the internet services.
			dispatchSms(internetServices, messages);
		} else {
			// There are no available SMS Internet Services, so dispatch to SmsModems
			List<SmsModem> sendingModems = getSmsModemsForSending(binary);
			dispatchSms(sendingModems, messages);
		}		
	}

	/**
	 * Dispatch some SMS {@link Message}s to some {@link SmsDevice}s. 
	 * @param devices
	 * @param messages
	 */
	private void dispatchSms(List<? extends SmsDevice> devices, List<Message> messages) {
		int deviceCount = devices.size();
		int messageIndex = -1;
		for(Message m : messages) {
			SmsDevice device = devices.get(++messageIndex % deviceCount);
			// Presumably the device will complain somehow if it is no longer connected
			// etc.  TODO we should actually check what happens!
			device.sendSMS(m);
			outgoingMessageEvent(device, m);
		}
	}

	/** Removes and returns all messages currently available in a list. */
	private List<Message> removeAll(ConcurrentLinkedQueue<Message> outbox) {
		LinkedList<Message> retrieved = new LinkedList<Message>();
		Message m;
		while((m=outbox.poll())!=null) retrieved.add(m);
		return retrieved;
	}

	/** @param binary <code>true</code> to get services for sending binary, <code>false</code> for services to send text  
	 * @return all {@link SmsInternetService} which are available for sending messages. */
	private List<SmsInternetService> getSmsInternetServicesForSending(boolean binary) {
		ArrayList<SmsInternetService> senders = new ArrayList<SmsInternetService>();
		for(SmsInternetService service : this.smsInternetServices) {
			if(service.isConnected() && service.isUseForSending() &&
					(!binary || service.isBinarySendingSupported())) {
				senders.add(service);
			}
		}
		return senders;
	}
	
	/** @param binary <code>true</code> to get phones for sending binary, <code>false</code> for phones to send text 
	 * @return all {@link SmsModem} which are available for sending messages. */
	private List<SmsModem> getSmsModemsForSending(boolean binary) {
		ArrayList<SmsModem> senders = new ArrayList<SmsModem>();
		for(SmsModem modem : this.phoneHandlers.values()) {
			if(modem.isRunning() && modem.isTimedOut()) {
				// The phone's being unresponsive.  Attempt to disconnect from the phone, remove the serial
				// number from the duplicates list and then add the phone to the reconnect list so we can
				// reconnect to it later.  We should also remove the unresponsive phone from the phoneHandlers
				// list.
				if(LOG.isDebugEnabled()) LOG.debug("Watchdog from phone [" + modem.getPort() + "] has timed out! Disconnecting...");
				handleDisconnect(modem);
			} else if(modem.isConnected() && modem.isUseForSending() &&
					(!binary || modem.isBinarySendingSupported())) {
				senders.add(modem);
			}
		}
		return senders;
	}
}

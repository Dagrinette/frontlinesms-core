/**
 * 
 */
package net.frontlinesms.ui.handler.message;

import static net.frontlinesms.FrontlineSMSConstants.COMMON_DATE;
import static net.frontlinesms.FrontlineSMSConstants.COMMON_MESSAGE;
import static net.frontlinesms.FrontlineSMSConstants.COMMON_RECIPIENT;
import static net.frontlinesms.FrontlineSMSConstants.COMMON_SENDER;
import static net.frontlinesms.FrontlineSMSConstants.COMMON_STATUS;
import static net.frontlinesms.FrontlineSMSConstants.MESSAGE_MESSAGES_DELETED;
import static net.frontlinesms.FrontlineSMSConstants.MESSAGE_REMOVING_MESSAGES;
import static net.frontlinesms.FrontlineSMSConstants.PROPERTY_FIELD;
import static net.frontlinesms.ui.UiGeneratorControllerConstants.COMPONENT_CB_CONTACTS;
import static net.frontlinesms.ui.UiGeneratorControllerConstants.COMPONENT_CB_GROUPS;
import static net.frontlinesms.ui.UiGeneratorControllerConstants.COMPONENT_FILTER_LIST;
import static net.frontlinesms.ui.UiGeneratorControllerConstants.COMPONENT_LB_COST;
import static net.frontlinesms.ui.UiGeneratorControllerConstants.COMPONENT_LB_MSGS_NUMBER;
import static net.frontlinesms.ui.UiGeneratorControllerConstants.COMPONENT_PN_BOTTOM;
import static net.frontlinesms.ui.UiGeneratorControllerConstants.COMPONENT_PN_FILTER;
import static net.frontlinesms.ui.UiGeneratorControllerConstants.COMPONENT_RECEIVED_MESSAGES_TOGGLE;
import static net.frontlinesms.ui.UiGeneratorControllerConstants.COMPONENT_SENT_MESSAGES_TOGGLE;
import static net.frontlinesms.ui.UiGeneratorControllerConstants.COMPONENT_TF_END_DATE;
import static net.frontlinesms.ui.UiGeneratorControllerConstants.COMPONENT_TF_START_DATE;

import java.text.ParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.log4j.Logger;

import thinlet.Thinlet;
import thinlet.ThinletText;

import net.frontlinesms.FrontlineSMSConstants;
import net.frontlinesms.Utils;
import net.frontlinesms.data.Order;
import net.frontlinesms.data.domain.Contact;
import net.frontlinesms.data.domain.Group;
import net.frontlinesms.data.domain.Keyword;
import net.frontlinesms.data.domain.Message;
import net.frontlinesms.data.domain.Message.Field;
import net.frontlinesms.data.repository.ContactDao;
import net.frontlinesms.data.repository.KeywordDao;
import net.frontlinesms.data.repository.MessageDao;
import net.frontlinesms.ui.Icon;
import net.frontlinesms.ui.UiGeneratorController;
import net.frontlinesms.ui.UiProperties;
import net.frontlinesms.ui.handler.BaseTabHandler;
import net.frontlinesms.ui.handler.ComponentPagingHandler;
import net.frontlinesms.ui.handler.PagedComponentItemProvider;
import net.frontlinesms.ui.i18n.InternationalisationUtils;

/**
 * @author Alex Anderson alex@frontlinesms.com
 * @author Carlos Eduardo Genz
 * <li> kadu(at)masabi(dot)com
 */
public class MessageHistoryTabHandler extends BaseTabHandler implements PagedComponentItemProvider {
	
//> CONSTANTS
	/** Path to the Thinlet XML layout file for the message history tab */
	private static final String UI_FILE_MESSAGES_TAB = "/ui/core/messages/messagesTab.xml";
	/** Path to the Thinlet XML layout file for the message details form */
	public static final String UI_FILE_MSG_DETAILS_FORM = "/ui/core/messages/dgMessageDetails.xml";

	/** UI Component name: the list of messages */
	public static final String COMPONENT_MESSAGE_LIST = "messageList";
	/** UI Component name: the list of groups */
	private static final String COMPONENT_GROUP_LIST = "messageHistory_groupList";
	
	/** Number of milliseconds in a day */
	private static final long MILLIS_PER_DAY = 24 * 60 * 60 * 1000;
	
//> INSTANCE PROPERTIES
	/** Logger */
	private final Logger LOG = Utils.getLogger(this.getClass());
	
	/** DAO for {@link Contact}s */
	private ContactDao contactDao;
	/** DAO for {@link Keyword}s */
	private KeywordDao keywordDao;
	/** DAO for {@link Message}s */
	private MessageDao messageDao;
	
//> UI COMPONENTS
	/** UI Component: table of messages */
	private Object messageListComponent;
	/** UI Component: checkbox: show sent messages? */
	private Object showSentMessagesComponent;
	/** UI Component: checkbox: show received messages? */
	private Object showReceivedMessagesComponent;
	/** UI Component: list of keywords or contacts */
	private Object filterListComponent;
	/** UI Component: tree of groups */
	private Object groupTreeComponent;

	/** Paging handler for the list of messages. */
	private ComponentPagingHandler messagePagingHandler;
	/** Paging handler for the lists of contacts and keywords. */
	private ComponentPagingHandler filterListPagingHandler;
	
	/** Start date of the message history, or <code>null</code> if none has been set. */
	private Long messageHistoryStart;
	/** End date of the message history, or <code>null</code> if none has been set. */
	private Long messageHistoryEnd;
	/** The number of people the current SMS will be sent to */
	private int numberToSend = 1;
	
//> CONSTRUCTORS
	/**
	 * @param ui value for {@link #ui}
	 * @param contactDao value for {@link #contactDao}
	 * @param keywordDao value for {@link #keywordDao}
	 * @param messageDao value for {@link #messageDao}
	 */
	public MessageHistoryTabHandler(UiGeneratorController ui, ContactDao contactDao, KeywordDao keywordDao, MessageDao messageDao) {
		super(ui);
		this.contactDao = contactDao;
		this.keywordDao = keywordDao;
		this.messageDao = messageDao;
	}

//> ACCESSORS
	/** Refresh the view. */
	public void refresh() {
		resetMessageHistoryFilter();
	}
	
	/**
	 * Shows the message history for the selected contact or group.
	 * @param component group list or contact list
	 */
	public void doShowMessageHistory(Object component) {
		Object attachment = ui.getAttachedObject(ui.getSelectedItem(component));
		
		boolean isGroup = attachment instanceof Group;
		boolean isContact = attachment instanceof Contact;
		boolean isKeyword = attachment instanceof Keyword;
		
		// Select the correct radio option
		ui.setSelected(find("cbContacts"), isContact);
		ui.setSelected(find("cbGroups"), isGroup);
		ui.setSelected(find("cbKeywords"), isKeyword);
		resetMessageHistoryFilter();
		
		// Find which list item should be selected
		Object list = isGroup ? groupTreeComponent : filterListComponent;
		boolean recurse = Thinlet.TREE.equals(Thinlet.getClass(list));
		Object next = ui.getNextItem(list, Thinlet.get(list, ":comp"), recurse);
		while(next != null && !ui.getAttachedObject(next).equals(attachment)) {
			next = ui.getNextItem(list, next, recurse);
		}
		// Little fix for groups - it seems that getNextItem doesn't return the root of the
		// tree, so we never get a positive match.
		if(next == null) next = ui.getItem(list, 0);
		ui.setSelectedItem(list, next);
		updateMessageList();
	}

//> INSTANCE HELPER METHODS
	/** Initialise the tab */
	protected Object initialiseTab() {
		LOG.trace("ENTRY");

		Object tabComponent = ui.loadComponentFromFile(UI_FILE_MESSAGES_TAB, this);
		
		messageListComponent = ui.find(tabComponent, COMPONENT_MESSAGE_LIST);
		messagePagingHandler = new ComponentPagingHandler(this.ui, this, this.messageListComponent);
		Object pnBottom = ui.find(tabComponent, COMPONENT_PN_BOTTOM);
		ui.add(pnBottom, messagePagingHandler.getPanel(), 0);

		filterListComponent = ui.find(tabComponent, COMPONENT_FILTER_LIST);
		filterListPagingHandler = new ComponentPagingHandler(this.ui, this, this.filterListComponent);
		Object pnFilter = ui.find(tabComponent, COMPONENT_PN_FILTER);
		ui.add(pnFilter, filterListPagingHandler.getPanel());
		
		groupTreeComponent = ui.find(tabComponent, COMPONENT_GROUP_LIST);

		// Set the types for the message list columns...
		initMessageTableForSorting();
		
		showReceivedMessagesComponent = ui.find(tabComponent, COMPONENT_RECEIVED_MESSAGES_TOGGLE);
		showSentMessagesComponent = ui.find(tabComponent, COMPONENT_SENT_MESSAGES_TOGGLE);
		
		LOG.trace("EXIT");
		return tabComponent;
	}
	
//> LIST PAGING METHODS
	/** @see PagedComponentItemProvider#getListItems(Object, int, int) */
	public Object[] getListItems(Object list, int start, int limit) {
		if(list.equals(this.messagePagingHandler.getList())) {
			List<Message> messages = getListMessages(list, start, limit);
			Object[] messageRows = new Object[messages.size()];
			for (int i = 0; i < messages.size(); i++) {
				Message m = messages.get(i);
				messageRows[i] = ui.getRow(m);
			}
			return messageRows;
		} else if(list.equals(this.filterListPagingHandler.getList())) {
			if(getMessageHistoryFilterType().equals(Contact.class)) {
				List<Contact> contacts = this.contactDao.getAllContacts(start, limit);
				Object[] contactRows = new Object[contacts.size() + 1];
				contactRows[0] = getAllMessagesListItem();
				for (int i = 0; i < contacts.size(); i++) {
					Contact c = contacts.get(i);
					contactRows[i+1] = ui.createListItem(c);
				}
				return contactRows;
			} else {
				List<Keyword> keywords = this.keywordDao.getAllKeywords(start, limit);
				Object[] keywordRows = new Object[keywords.size() + 1];
				keywordRows[0] = getAllMessagesListItem();
				for (int i = 0; i < keywords.size(); i++) {
					Keyword k = keywords.get(i);
					keywordRows[i+1] = ui.createListItem(k);
				}
				return keywordRows;
			}
		} else throw new IllegalStateException();
	}
	/** @see PagedComponentItemProvider#getTotalListItemCount(Object) */
	public int getTotalListItemCount(Object list) {
		if(list.equals(this.messagePagingHandler.getList())) {
			int messageCount = getMessageCount();
			numberToSend = messageCount;
			return messageCount;
		} else if(list.equals(this.filterListPagingHandler.getList())) {
			if(getMessageHistoryFilterType().equals(Contact.class)) {
				return this.contactDao.getContactCount();
			} else {
				return this.keywordDao.getTotalKeywordCount();
			}
		} else {
			throw new IllegalStateException();
		}
	}
	
	/** @return total number of messages to be displayed in the message list. */
	private int getMessageCount() {
		Class<?> filterClass = getMessageHistoryFilterType();
		Object filterList = filterClass == Group.class ? groupTreeComponent
														: filterListComponent;
		Object selectedItem = ui.getSelectedItem(filterList);

		if (selectedItem == null) {
			return 0;
		} else {
			final int messageType = getSelectedMessageType();
			int selectedIndex = ui.getSelectedIndex(filterList);
			if (selectedIndex == 0) {
				return messageDao.getMessageCount(messageType, messageHistoryStart, messageHistoryEnd);
			} else {
				if(filterClass == Contact.class) {
					Contact c = ui.getContact(selectedItem);
					return messageDao.getMessageCountForMsisdn(messageType, c.getPhoneNumber(), messageHistoryStart, messageHistoryEnd);
				} else if(filterClass == Group.class) {
					// A Group was selected
					List<Group> groups = new ArrayList<Group>();
					ui.getGroupsRecursivelyDown(groups, ui.getGroup(selectedItem));
					return messageDao.getMessageCountForGroups(messageType, groups, messageHistoryStart, messageHistoryEnd);
				} else /* (filterClass == Keyword.class) */ {
					// Keyword Selected
					Keyword k = ui.getKeyword(selectedItem);
					return messageDao.getMessageCount(messageType, k, messageHistoryStart, messageHistoryEnd);
				}
			}
		}
	}
	
	/**
	 * Gets the list of messages to display in the message table.
	 * @param list The message table object
	 * @param start The index of the first message to return
	 * @param limit The maximum number of messages to return
	 * @return a page of messages, sorted and filtered
	 */
	private List<Message> getListMessages(Object list, int start, int limit) {
		Class<?> filterClass = getMessageHistoryFilterType();
		Object filterList = filterClass == Group.class ? groupTreeComponent
				: filterListComponent;
		Object selectedItem = ui.getSelectedItem(filterList);
		
		if (selectedItem == null) {
			return Collections.emptyList();
		} else {
			int messageType = getSelectedMessageType();
			Order order = getMessageSortOrder();
			Field field = getMessageSortField();
			
			int selectedIndex = ui.getSelectedIndex(filterList);
			if (selectedIndex == 0) {
				List<Message> allMessages = messageDao.getAllMessages(messageType, field, order, messageHistoryStart, messageHistoryEnd, start, limit);
				return allMessages;
			} else {
				if(filterClass == Contact.class) {
					// Contact selected
					Contact c = ui.getContact(selectedItem);
					return messageDao.getMessagesForMsisdn(messageType, c.getPhoneNumber(), field, order, messageHistoryStart, messageHistoryEnd, start, limit);
				} else if(filterClass == Group.class) {
					// A Group was selected
					List<Group> groups = new ArrayList<Group>();
					ui.getGroupsRecursivelyDown(groups, ui.getGroup(selectedItem));
					return messageDao.getMessagesForGroups(messageType, groups, field, order, messageHistoryStart, messageHistoryEnd, start, limit);
				} else /* (filterClass == Keyword.class) */ {
					// Keyword Selected
					Keyword k = ui.getKeyword(selectedItem);
					return messageDao.getMessagesForKeyword(messageType, k, field, order, messageHistoryStart, messageHistoryEnd, start, limit);
				}
			}
		}
	}
	
	/** @return the field to sort messages in the message list by */
	private Field getMessageSortField() {
		Object header = Thinlet.get(messageListComponent, ThinletText.HEADER);
		Object tableColumn = ui.getSelectedItem(header);
		Message.Field field = Message.Field.DATE;
		if (tableColumn != null) {
			field = (Message.Field) ui.getProperty(tableColumn, PROPERTY_FIELD);
		}
		
		return field;
	}
	
	/** @return the sorting order for the message list */
	private Order getMessageSortOrder() {
		Object header = Thinlet.get(messageListComponent, ThinletText.HEADER);
		Object tableColumn = ui.getSelectedItem(header);
		Order order = Order.DESCENDING;
		if (tableColumn != null) {
			order = Thinlet.get(tableColumn, ThinletText.SORT).equals(ThinletText.ASCENT) ? Order.ASCENDING : Order.DESCENDING;
		}

		return order;
	}
	
	/** @return he type(s) of messages to display in the message list */
	private int getSelectedMessageType() {
		boolean showSentMessages = ui.isSelected(showSentMessagesComponent);
		boolean showReceivedMessages = ui.isSelected(showReceivedMessagesComponent);
		int messageType;
		if (showSentMessages && showReceivedMessages) { 
			messageType = Message.TYPE_ALL;
		} else if (showSentMessages) {
			messageType = Message.TYPE_OUTBOUND;
		} else messageType = Message.TYPE_RECEIVED;
		return messageType;
	}
	
//> PUBLIC UI METHODS
	/** Method called when the selected filter is changed. */
	public void messageHistory_filterChanged() {
		resetMessageHistoryFilter();
	}
	
	/**
	 * Shows the export wizard dialog for exporting contacts.
	 * @param list The list to get selected items from.
	 */
	public void showExportWizard(Object list) {
		this.ui.showExportWizard(list, "messages");
	}

	/**
	 * Event triggered when the date has been changed for the message history.
	 * The messages should be re-filtered with the new dates.
	 */
	public void messageHistoryDateChanged() {
		Long newStart = null;
		try {
			String tfStartDateValue = ui.getText(find(COMPONENT_TF_START_DATE));
			newStart = InternationalisationUtils.parseDate(tfStartDateValue).getTime();
		} catch (ParseException ex) {}
		Long newEnd = null;
		try {
			String tfEndDateValue = ui.getText(find(COMPONENT_TF_END_DATE));
			newEnd = InternationalisationUtils.parseDate(tfEndDateValue).getTime() + MILLIS_PER_DAY;
		} catch (ParseException ex) {}
		
		if (newStart != messageHistoryStart || newEnd != messageHistoryEnd) {
			messageHistoryStart = newStart;
			messageHistoryEnd = newEnd;
			updateMessageList();
		}
	}
	
	/** @deprecated this should be private */
	public void updateMessageHistoryCost() {
		LOG.trace("ENTRY");
		
		ui.setText(find(COMPONENT_LB_MSGS_NUMBER), String.valueOf(numberToSend));		
		ui.setText(find(COMPONENT_LB_COST), InternationalisationUtils.formatCurrency(UiProperties.getInstance().getCostPerSms() * numberToSend));
		
		LOG.trace("EXIT");
	}

	/**
	 * Method called when there is a change in the selection of Sent and Received messages.
	 * @param checkbox
	 */
	public void toggleMessageListOptions(Object checkbox) {
		boolean showSentMessages = ui.isSelected(showSentMessagesComponent);
		boolean showReceivedMessages = ui.isSelected(showReceivedMessagesComponent);

		// One needs to be on, so if both have just been switched off, we need to turn the other back on.
		if (!showSentMessages && !showReceivedMessages) {
			if(checkbox == showSentMessagesComponent) {
				ui.setSelected(showReceivedMessagesComponent, true);
			} else {
				ui.setSelected(showSentMessagesComponent, true);
			}
		}
		updateMessageList();
	}
	
	/** Update the list of messages. */
	public void updateMessageList() {
		this.messagePagingHandler.setCurrentPage(0);
		this.messagePagingHandler.refresh();
	}
	
	/** Reset the message history filter. */
	private void resetMessageHistoryFilter() {
		Class<?> filterClass = getMessageHistoryFilterType();
		if(filterClass == Group.class) {
			// Clear and reload the group list
			ui.removeAll(groupTreeComponent);
			ui.add(groupTreeComponent, ui.getNode(ui.getRootGroup(), false));
		} else {
			this.filterListPagingHandler.refresh();
		}
		
		ui.setVisible(filterListComponent, filterClass != Group.class);
		ui.setVisible(groupTreeComponent, filterClass == Group.class);
		// Group tree doesn't need paging, so hide the paging controls. 
		ui.setVisible(this.filterListPagingHandler.getPanel(), filterClass != Group.class);
	}

	public void messageHistory_enableSend(Object popUp, boolean isKeyword) {
		boolean toSet = ui.getSelectedIndex(filterListComponent) > 0;
		toSet = toSet && !isKeyword;
		ui.setVisible(popUp, toSet);
	}

	/**
	 * Event triggered when an outgoing message is created or updated.
	 * @param message The message involved in the event
	 */
	public synchronized void outgoingMessageEvent(Message message) {
		LOG.debug("Refreshing message list");
		
		// If the message is already in the list, we just need to update its row
		for (int i = 0; i < ui.getItems(messageListComponent).length; i++) {
			Message e = ui.getMessage(ui.getItem(messageListComponent, i));
			if (e.equals(message)) {
				ui.remove(ui.getItem(messageListComponent, i));
				ui.add(messageListComponent, ui.getRow(message), i);
				return;
			}
		}
		
		// If the message is not already in the list, add it if relevant
		addMessageToList(message);
	}
	
	/**
	 * Event triggered when an incoming message arrives.
	 * @param message The message involved in the event
	 */
	public synchronized void incomingMessageEvent(Message message) {
		addMessageToList(message);
	}
	
	public void messagesTab_removeMessages() {
		LOG.trace("ENTER");
		
		ui.removeConfirmationDialog();
		ui.setStatus(InternationalisationUtils.getI18NString(MESSAGE_REMOVING_MESSAGES));

		final Object[] selected = ui.getSelectedItems(messageListComponent);
		int numberRemoved = 0;
		for(Object o : selected) {
			Message toBeRemoved = ui.getMessage(o);
			LOG.debug("Message [" + toBeRemoved + "]");
			int status = toBeRemoved.getStatus();
			if (status != Message.STATUS_PENDING) {
				LOG.debug("Removing Message [" + toBeRemoved + "] from database.");
				if (status == Message.STATUS_OUTBOX) {
					// FIXME should not be getting the phone manager like this - should be a local propery i rather think
					ui.getPhoneManager().removeFromOutbox(toBeRemoved);
				}
				numberToSend -= toBeRemoved.getNumberOfSMS();
				messageDao.deleteMessage(toBeRemoved);
				numberRemoved++;
			} else {
				LOG.debug("Message status is [" + toBeRemoved.getStatus() + "], so we do not remove!");
			}
		}
		if (numberRemoved > 0) {
			ui.setStatus(InternationalisationUtils.getI18NString(MESSAGE_MESSAGES_DELETED));
			updateMessageList();
		}
		
		LOG.trace("EXIT");
	}

	/** Show the details of the message selected in {@link #messageListComponent}. */
	public void showMessageDetails() {
		Object selected = ui.getSelectedItem(this.messageListComponent);
		if (selected != null) {
			Message message = ui.getMessage(selected);
			Object details = ui.loadComponentFromFile(UI_FILE_MSG_DETAILS_FORM, this);
			String senderDisplayName = ui.getSenderDisplayValue(message);
			String recipientDisplayName = ui.getRecipientDisplayValue(message);
			String status = UiGeneratorController.getMessageStatusAsString(message);
			String date = InternationalisationUtils.getDatetimeFormat().format(message.getDate());
			String content = message.getTextContent();
			
			ui.setText(ui.find(details, "tfStatus"), status);
			ui.setText(ui.find(details, "tfSender"), senderDisplayName);
			ui.setText(ui.find(details, "tfRecipient"), recipientDisplayName);
			ui.setText(ui.find(details, "tfDate"), date);
			ui.setText(ui.find(details, "tfContent"), content);
			
			ui.add(details);
		}
	}
	
	/**
	 * Re-Sends the selected messages and updates the list with the supplied page number afterwards.
	 * 
	 * @param pageNumber
	 * @param resultsPerPage
	 * @param object
	 */
	public void resendSelectedFromMessageList(Object object) {
		Object[] selected = ui.getSelectedItems(object);
		for (Object o : selected) {
			Message toBeReSent = ui.getMessage(o);
			int status = toBeReSent.getStatus();
			if (status == Message.STATUS_FAILED) {
				toBeReSent.setSenderMsisdn("");
				toBeReSent.setRetriesRemaining(Message.MAX_RETRIES);
				ui.getPhoneManager().sendSMS(toBeReSent);
			} else if (status == Message.STATUS_DELIVERED || status == Message.STATUS_SENT) {
				ui.getFrontlineController().sendTextMessage(toBeReSent.getRecipientMsisdn(), toBeReSent.getTextContent());
			}
		}
	}
	
	/**
	 * Enables or disables menu options in a List Component's popup list
	 * and toolbar.  These enablements are based on whether any items in
	 * the list are selected, and if they are, on the nature of these
	 * items.
	 * @param list the list
	 * @param popupMenu the popup menu the list refers to
	 */
	public void enableOptions(Object list, Object popupMenu) {
		Object[] selectedItems = ui.getSelectedItems(list);
		boolean hasSelection = selectedItems.length > 0;
		
		// If nothing is selected, hide the popup menu
		ui.setVisible(popupMenu, hasSelection);
		
		if (hasSelection) {
			// If we are looking at a list of messages, there are certain popup menu items that
			// should or shouldn't be enabled, depending on the type of messages we have selected.
			boolean receivedMessagesSelected = false;
			boolean sentMessagesSelected = false;
			for(Object selectedComponent : selectedItems) {
				Message attachedMessage = ui.getAttachedObject(selectedComponent, Message.class);
				if(attachedMessage.getType() == Message.TYPE_RECEIVED) {
					receivedMessagesSelected = true;
				}
				if(attachedMessage.getType() == Message.TYPE_OUTBOUND) {
					sentMessagesSelected = true;
				}
			}
			
			for (Object popupMenuItem : ui.getItems(popupMenu)) {
				String popupMenuItemName = ui.getName(popupMenuItem);
				boolean visible = hasSelection;
				if(popupMenuItemName.equals("miReply")) {
					visible = receivedMessagesSelected;
				}
				if(popupMenuItemName.equals("miResend")) {
					visible = sentMessagesSelected;
				}
				ui.setVisible(popupMenuItem, visible);
			}
		}
	}

//> UI HELPER METHODS
	/** Initialise the message table's HEADER component for sorting the table. */
	private void initMessageTableForSorting() {
		Object header = Thinlet.get(messageListComponent, ThinletText.HEADER);
		for (Object o : ui.getItems(header)) {
			String text = ui.getString(o, Thinlet.TEXT);
			// Here, the FIELD property is set on each column of the message table.  These field objects are
			// then used for easy sorting of the message table.
			if(text != null) {
				if (text.equalsIgnoreCase(InternationalisationUtils.getI18NString(COMMON_STATUS))) ui.putProperty(o, PROPERTY_FIELD, Message.Field.STATUS);
				else if(text.equalsIgnoreCase(InternationalisationUtils.getI18NString(COMMON_DATE))) ui.putProperty(o, PROPERTY_FIELD, Message.Field.DATE);
				else if(text.equalsIgnoreCase(InternationalisationUtils.getI18NString(COMMON_SENDER))) ui.putProperty(o, PROPERTY_FIELD, Message.Field.SENDER_MSISDN);
				else if(text.equalsIgnoreCase(InternationalisationUtils.getI18NString(COMMON_RECIPIENT))) ui.putProperty(o, PROPERTY_FIELD, Message.Field.RECIPIENT_MSISDN);
				else if(text.equalsIgnoreCase(InternationalisationUtils.getI18NString(COMMON_MESSAGE))) ui.putProperty(o, PROPERTY_FIELD, Message.Field.MESSAGE_CONTENT);
			}
		}
	}

	/**
	 * Adds a message to the list we are currently viewing, if it is relevant.
	 * We just add the message to the top of the list, as things will get rather complicated otherwise
	 * @param message the message to add
	 */
	private void addMessageToList(Message message) {
		LOG.trace("ENTER");
		LOG.debug("Message [" + message + "]");
		Object sel = ui.getSelectedItem(filterListComponent);
		boolean sent = ui.isSelected(showSentMessagesComponent);
		boolean received = ui.isSelected(showReceivedMessagesComponent);
		if (sel != null && ((sent && message.getType() == Message.TYPE_OUTBOUND) || (received && message.getType() == Message.TYPE_RECEIVED))) {
			boolean toAdd = false;
			if (ui.getSelectedIndex(filterListComponent) == 0) {
				toAdd = true;
			} else {
				if (ui.isSelected(find(COMPONENT_CB_CONTACTS))) {
					Contact c = ui.getContact(sel);
					LOG.debug("Contact selected [" + c.getName() + "]");
					if (message.getSenderMsisdn().endsWith(c.getPhoneNumber()) 
							|| message.getRecipientMsisdn().endsWith(c.getPhoneNumber())) {
						toAdd = true;
					}
				} else if (ui.isSelected(find(COMPONENT_CB_GROUPS))) {
					Group g = ui.getGroup(sel);
					LOG.debug("Group selected [" + g.getName() + "]");
					if (g.equals(ui.getRootGroup())) {
						toAdd = true;
					} else {
						List<Group> groups = new ArrayList<Group>();
						ui.getGroupsRecursivelyUp(groups, g);
						Contact sender = contactDao.getFromMsisdn(message.getSenderMsisdn());
						Contact receiver = contactDao.getFromMsisdn(message.getRecipientMsisdn());
						for (Group gg : groups) {
							if ( (sender != null && sender.isMemberOf(gg)) 
									|| (receiver != null && receiver.isMemberOf(gg))) {
								toAdd = true;
								break;
							}
						}
					}
				} else {
					Keyword selected = ui.getKeyword(sel);
					LOG.debug("Keyword selected [" + selected.getKeyword() + "]");
					Keyword keyword = keywordDao.getFromMessageText(message.getTextContent());
					toAdd = selected.equals(keyword);
				}
			}
			if (toAdd) {
				LOG.debug("Time to try to add this message to list...");
				if (ui.getItems(messageListComponent).length < this.messagePagingHandler.getMaxItemsPerPage()) {
					LOG.debug("There's space! Adding...");
					ui.add(messageListComponent, ui.getRow(message));
					ui.setEnabled(messageListComponent, true);
					if (message.getType() == Message.TYPE_OUTBOUND) {
						numberToSend += message.getNumberOfSMS();
						updateMessageHistoryCost();
					}
				}
			}
		}
		LOG.trace("EXIT");
	}
	
	/**
	 * Gets the selected filter type for the message history, i.e. Contact, Group or Keyword.
	 * @return {@link Contact}, {@link Group} or {@link Keyword}, depending which is set for the message filter.
	 */
	private Class<?> getMessageHistoryFilterType() {
		if(ui.isSelected(find(COMPONENT_CB_CONTACTS))) return Contact.class;
		else if(ui.isSelected(find(COMPONENT_CB_GROUPS))) return Group.class;
		else return Keyword.class;
	}
	
	/** @return list item component representing ALL MESSAGES in the system */
	private Object getAllMessagesListItem() {
		Object allMessages = ui.createListItem(InternationalisationUtils.getI18NString(FrontlineSMSConstants.COMMON_ALL_MESSAGES), null);
		ui.setIcon(allMessages, Icon.SMS_HISTORY);
		ui.add(filterListComponent, allMessages);
		return allMessages;
	}
	
//> UI PASS-THROUGH METHODS
	/** @see UiGeneratorController#show_composeMessageForm(Object) */
	public void show_composeMessageForm(Object list) {
		this.ui.show_composeMessageForm(list);
	}
	/** @see UiGeneratorController#groupList_expansionChanged(Object) */
	public void groupList_expansionChanged(Object groupList) {
		this.ui.groupList_expansionChanged(groupList);
	}
	/** @see UiGeneratorController#showDateSelecter(Object) */
	public void showDateSelecter(Object textField) {
		this.ui.showDateSelecter(textField);
	}
}
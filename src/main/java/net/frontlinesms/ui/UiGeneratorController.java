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
package net.frontlinesms.ui;

import java.awt.Font;
import java.awt.Frame;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.text.ParseException;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.mail.MessagingException;

import net.frontlinesms.AppProperties;
import net.frontlinesms.BuildProperties;
import net.frontlinesms.EmailSender;
import net.frontlinesms.ErrorUtils;
import net.frontlinesms.FrontlineSMS;
import net.frontlinesms.FrontlineSMSConstants;
import net.frontlinesms.PluginManager;
import net.frontlinesms.Utils;
import net.frontlinesms.data.*;
import net.frontlinesms.data.domain.*;
import net.frontlinesms.data.repository.*;
import net.frontlinesms.debug.RandomDataGenerator;
import net.frontlinesms.listener.EmailListener;
import net.frontlinesms.listener.UIListener;
import net.frontlinesms.plugins.PluginController;
import net.frontlinesms.plugins.PluginControllerProperties;
import net.frontlinesms.plugins.PluginProperties;
import net.frontlinesms.resources.ResourceUtils;
import net.frontlinesms.smsdevice.*;
import net.frontlinesms.smsdevice.internet.SmsInternetService;
import net.frontlinesms.ui.handler.HomeTabHandler;
import net.frontlinesms.ui.handler.PhoneTabHandler;
import net.frontlinesms.ui.handler.contacts.ContactsTabHandler;
import net.frontlinesms.ui.handler.contacts.GroupSelecterPanel;
import net.frontlinesms.ui.handler.contacts.SingleGroupSelecterPanelOwner;
import net.frontlinesms.ui.handler.core.DatabaseSettingsPanel;
import net.frontlinesms.ui.handler.email.EmailAccountDialogHandler;
import net.frontlinesms.ui.handler.email.EmailTabHandler;
import net.frontlinesms.ui.handler.keyword.KeywordTabHandler;
import net.frontlinesms.ui.handler.message.MessageHistoryTabHandler;
import net.frontlinesms.ui.handler.message.MessagePanelHandler;
import net.frontlinesms.ui.i18n.FileLanguageBundle;
import net.frontlinesms.ui.i18n.InternationalisationUtils;
import net.frontlinesms.ui.i18n.LanguageBundle;

import org.apache.log4j.Logger;

import thinlet.FrameLauncher;
import thinlet.Thinlet;

// FIXME should not be using static imports
import static net.frontlinesms.FrontlineSMSConstants.*;
import static net.frontlinesms.ui.UiGeneratorControllerConstants.*;

/**
 * Class containing control methods for the Thinlet-driven GUI.
 * 
 * The public (void) methods in this class are called by reflection via the Thinlet class.
 * 
 * Employed within are a selection of different methods for essentially getting the same
 * thing done, e.g. caching of components at class level vs. searching every time they
 * are to be used.  This is because design methods have changed throughout the development
 * of this class.  Currently searching for components as and when they are needed is
 * favoured, and so this should be done where possible.
 * 
 * We're now in the process of separating this class into smaller classes which control separate,
 * modular parts of the UI, e.g. the {@link HomeTabHandler}.
 * 
 * @author Alex Anderson 
 * <li> alex(at)masabi(dot)com
 * @author Carlos Eduardo Genz
 * <li> kadu(at)masabi(dot)com
 */
@SuppressWarnings("serial")
public class UiGeneratorController extends FrontlineUI implements EmailListener, UIListener, SingleGroupSelecterPanelOwner {

//> CONSTANTS
	/** Default height of the Thinlet frame launcher */
	public static final int DEFAULT_HEIGHT = 768;
	/** Default width of the Thinlet frame launcher */
	public static final int DEFAULT_WIDTH = 1024;

//> INSTANCE PROPERTIES
	/** Logging object */
	public Logger LOG = Utils.getLogger(UiGeneratorController.class);
	
	/** The {@link FrontlineSMS} instance that this UI is attached to. */
	private FrontlineSMS frontlineController;
	
	/** The INTERNAL NAME of the tab (a thinlet UI component) currently active */
	private String currentTab;
	
	/** The manager of {@link SmsDevice}s */
	private final SmsDeviceManager phoneManager;
	/** Manager of {@link PluginController}s */
	private final PluginManager pluginManager;
	
	/** Data Access Object for {@link Contact}s */
	private final ContactDao contactDao;
	/** Data Access Object for {@link Group}s */
	private final GroupDao groupDao;
	/** Data Access Object for {@link GroupMembership}s */
	private final GroupMembershipDao groupMembershipDao;
	/** Data Access Object for {@link Message}s */
	private final MessageDao messageFactory;
	/** Data Access Object for {@link Keyword}s */
	private final KeywordDao keywordDao;
	/** Data Access Object for {@link SmsModemSettings}s */
	private final SmsModemSettingsDao phoneDetailsManager;

	/** Controller of the home tab. */
	private final HomeTabHandler homeTabController;
	/** Controller of the phones tab. */
	private final PhoneTabHandler phoneTabController;
	/** Controller of the contacts tab. */
	private final ContactsTabHandler contactsTabController;
	/** Controller of the keywords tab. */
	private final KeywordTabHandler keywordTabHandler;
	/** Controller of the message tab. */
	private final MessageHistoryTabHandler messageTabController;
	/** Handler for the email tab. */
	private final EmailTabHandler emailTabHandler;
	
	// FIXME these should probably move to the contacts tab controller
	/** Fake group: The root group, of which all other top-level groups are children.  The name of this group specified in the constructor will not be used due to overridden {@link Group#getName()}. */
	final Group rootGroup = new Group(null, "Root Group [i18n]") {
		@Override
		/** Provide an internationalised version of this group's name */
		public String getName() {
			return InternationalisationUtils.getI18NString(FrontlineSMSConstants.CONTACTS_ALL);
		}
	};
	/** Fake group: all contacts without a name set.  The name of this group specified in the constructor will not be used due to overridden {@link Group#getName()}. */
	final Group unnamedContacts = new Group(null, "Unnamed [i18n]") {
		@Override
		/** Provide an internationalised version of this group's name */
		public String getName() {
			return InternationalisationUtils.getI18NString(FrontlineSMSConstants.CONTACTS_UNNAMED);
		}
	};
	/** Fake group: all contacts not a member of a group.  The name of this group specified in the constructor will not be used due to overridden {@link Group#getName()}. */
	final Group ungroupedContacts = new Group(null, "Ungrouped [i18n]") {
		@Override
		/** Provide an internationalised version of this group's name */
		public String getName() {
			return InternationalisationUtils.getI18NString(FrontlineSMSConstants.CONTACTS_UNGROUPED);
		}
	};

	/** The number of people the current SMS will be sent to
	 * TODO this is a very strange variable to have.  This should be replaced with context-specific tracking of the number of messages to be sent. */
	private int numberToSend = 1;
	
	/** Thinlet UI Component: status bar at the bottom of the window */
	private final Object statusBarComponent;
	
	/**
	 * Creates a new instance of the UI Controller.
	 * @param frontlineController The {@link FrontlineSMS} instance that this class is tied to.
	 * @param detectPhones <code>true</code> if phone detection should be started automatically; <code>false</code> otherwise.
	 * @throws Throwable any unhandled {@link Throwable} from this method
	 */
	public UiGeneratorController(FrontlineSMS frontlineController, boolean detectPhones) throws Throwable {
		this.frontlineController = frontlineController;
		
		// Load the requested language file.
		AppProperties appProperties = AppProperties.getInstance();
		String currentLanguageFile = appProperties.getLanguageFilePath();
		if (currentLanguageFile != null) {
			LanguageBundle languageBundle = InternationalisationUtils.getLanguageBundle(new File(currentLanguageFile));
			FrontlineUI.currentResourceBundle = languageBundle;
			setResourceBundle(languageBundle.getProperties(), languageBundle.isRightToLeft());
			Font requestedFont = languageBundle.getFont();
			if(requestedFont != null) {
				setFont(new Font(requestedFont.getName(), getFont().getStyle(), getFont().getSize()));
			}
			LOG.debug("Loaded language from file: " + currentLanguageFile);
		}
		
		this.phoneManager = frontlineController.getSmsDeviceManager();
		this.contactDao = frontlineController.getContactDao();
		this.groupDao = frontlineController.getGroupDao();
		this.groupMembershipDao = frontlineController.getGroupMembershipDao();
		this.messageFactory = frontlineController.getMessageDao();
		this.keywordDao = frontlineController.getKeywordDao();
		this.phoneDetailsManager = frontlineController.getSmsModemSettingsDao();
		this.pluginManager = frontlineController.getPluginManager();
		
		// Load the data mode from the ui.properties file
		UiProperties uiProperties = UiProperties.getInstance();
		LOG.debug("Detect Phones [" + detectPhones + "]");
		
		try {
			add(loadComponentFromFile(UI_FILE_HOME));
			statusBarComponent = find(COMPONENT_STATUS_BAR);
			setStatus(InternationalisationUtils.getI18NString(MESSAGE_STARTING));
			
			// Find the languages submenu, and add all present language packs to it
			addLanguageMenu(find("menu_language"));
			
			setText(find(COMPONENT_TF_COST_PER_SMS), InternationalisationUtils.formatCurrency(this.getCostPerSms(), false));
			setText(find(COMPONENT_LB_COST_PER_SMS_PREFIX),
					InternationalisationUtils.isCurrencySymbolPrefix() 
							? InternationalisationUtils.getCurrencySymbol()
							: "");
			setText(find(COMPONENT_LB_COST_PER_SMS_SUFFIX),
					InternationalisationUtils.isCurrencySymbolSuffix() 
					? InternationalisationUtils.getCurrencySymbol()
					: "");
			
			Object tabbedPane = find(COMPONENT_TABBED_PANE);
			
			this.phoneTabController = new PhoneTabHandler(this);
			this.phoneTabController.init();
			
			this.contactsTabController = new ContactsTabHandler(this, this.contactDao, this.groupDao);
			this.contactsTabController.init();
			
			this.messageTabController = new MessageHistoryTabHandler(this, contactDao, keywordDao, messageFactory);
			this.messageTabController.init();
			
			this.emailTabHandler = new EmailTabHandler(this, this.frontlineController);
			this.emailTabHandler.init();
			
			this.keywordTabHandler = new KeywordTabHandler(this, this.frontlineController);
			this.keywordTabHandler.init();

			this.homeTabController = new HomeTabHandler(this);
			this.homeTabController.init();
			
			if (uiProperties.isTabVisible("hometab")) {
				add(tabbedPane, this.homeTabController.getTab());
				setSelected(find(COMPONENT_MI_HOME), true);
			}
			add(tabbedPane, this.contactsTabController.getTab());
			if (uiProperties.isTabVisible("keywordstab")) {
				add(tabbedPane, this.keywordTabHandler.getTab());
				setSelected(find(COMPONENT_MI_KEYWORD), true);
			}
			if(uiProperties.isTabVisible("messagetab")) {
				add(tabbedPane, this.messageTabController.getTab());
			}
			if (uiProperties.isTabVisible("emailstab")) {
				add(tabbedPane, this.emailTabHandler.getTab());
				setSelected(find(COMPONENT_MI_EMAIL), true);
			}
			add(tabbedPane, phoneTabController.getTab());
			
			// Initialise the plugins menu
			Object pluginMenu = find("menu_tabs");
			for(Class<PluginController> pluginClass : PluginProperties.getInstance().getPluginClasses()) {
				// Try to get an icon from the classpath
				String pluginName;
				String iconPath;
				if(pluginClass.isAnnotationPresent(PluginControllerProperties.class)) {
					PluginControllerProperties properties = pluginClass.getAnnotation(PluginControllerProperties.class);
					pluginName = properties.name();
					iconPath = properties.iconPath();
				} else {
					pluginName = pluginClass.getSimpleName();
					iconPath = '/' + pluginClass.getPackage().getName().replace('.', '/') + '/' + pluginClass.getSimpleName() + ".png";
				}
				Object menuItem = createCheckboxMenuitem(iconPath, pluginName, PluginProperties.getInstance().isPluginEnabled(pluginClass));
				add(pluginMenu, menuItem);
				setAction(menuItem, "updatePluginEnabled('"+pluginClass.getName()+"', this.selected)", pluginMenu, this);
			}
			
			// Add plugins tabs
			for(PluginController controller : this.pluginManager.getPluginControllers()) {
				addPluginTextResources(controller);
				add(tabbedPane, controller.getTab(this));
			}
			
			currentTab = TAB_HOME;

			// Try to add the emulator number to the contacts
			try {
				Contact testContact = new Contact(TEST_NUMBER_NAME, EMULATOR_MSISDN, "", "", "", true);
				contactDao.saveContact(testContact);
			} catch(DuplicateKeyException ex) {
				LOG.debug("Contact already exists", ex);
			}
			
			// Initialise the phone manager, and start auto-detection of connected phones.
			setStatus(InternationalisationUtils.getI18NString(MESSAGE_INITIALISING_PHONE_MANAGER));

			//Window size				
			Integer width = uiProperties.getWindowWidth();
			if(width == null) width = DEFAULT_WIDTH;
			
			Integer height = uiProperties.getWindowHeight();
			if(height == null) height = DEFAULT_HEIGHT;
			
			final String WINDOW_TITLE = "FrontlineSMS " + BuildProperties.getInstance().getVersion();
			frameLauncher = new FrameLauncher(WINDOW_TITLE, this, width, height, getIcon(Icon.FRONTLINE_ICON));
			if (uiProperties.isWindowStateMaximized()) {
				//Is maximised
				frameLauncher.setExtendedState(Frame.MAXIMIZED_BOTH);
			}
			
			// Set up the close event on the framelauncher
			frameLauncher.addWindowListener(new WindowAdapter() {
				@Override
				public void windowClosing(WindowEvent e) {
					super.windowClosing(e);
					// Make sure the exit() method is called so that we shut down cleanly
					LOG.trace(".windowClosing()");
					exit();
				}
			});
			
			frontlineController.setEmailListener(this);
			frontlineController.setUiListener(this);
			frontlineController.setSmsDeviceEventListener(this.phoneTabController);
			
			setStatus(InternationalisationUtils.getI18NString(MESSAGE_PHONE_MANAGER_INITIALISED));
			
			if (detectPhones) {
				phoneTabController.phoneManager_detectModems();
			}
		} catch(Throwable t) {
			LOG.error("Problem starting User Interface module.", t);
			super.destroy();
			throw t;
		}
	}
	
	/**
	 * 
	 * @param pluginClassName the fully qualified name of the plugin class
	 * @param enabled <code>true</code> if the plugin should be enabled by this method, <code>false</code> if it should be disabled
	 */
	public void updatePluginEnabled(String pluginClassName, boolean enabled) {
		if(LOG.isTraceEnabled()) {
			LOG.trace("UiGeneratorController.updatePluginEnabled()");
			LOG.trace("\tclass   : " + pluginClassName);
			LOG.trace("\tenabled : " + enabled);
		}

		Class<PluginController> pluginClass = PluginProperties.getInstance().getPluginClass(pluginClassName);
		
		// 1st, check if the plugin is already in the state the user is trying to put it in
		if(enabled == PluginProperties.getInstance().isPluginEnabled(pluginClassName)) {
			// The plugin is already in the expected state, so we should do nothing
			return;
		} else {
			if(enabled) {
				try {
					// If we are enabling the plugin, we need to load it, add it to the loaded plugins list, and
					// finally add its tab to the UI
					PluginController controller = this.pluginManager.loadPluginController(pluginClassName);
					addPluginTextResources(controller);
					this.pluginManager.initPluginController(controller);
					this.add(find(COMPONENT_TABBED_PANE), controller.getTab(this));
				} catch(Throwable t) {
					// There was a problem initialising the plugin.  Log this, warn the user, and do
					// not enable the plugin in the properties.
					LOG.warn("There was a problem initialising the plugin.", t);
					// TODO we should probably make this warning for the user slightly more elegant.
					throw new RuntimeException(t);
				}
			} else {
				// If we are disabling a plugin, we need to remove its tab from the UI, and then discard it
				// Get the instance of the controller
				PluginController controller = null;
				for(PluginController c : this.pluginManager.getPluginControllers()) {
					if(pluginClass.isInstance(c)) {
						controller = c;
						break;
					}
				}
				
				if(controller == null) {
					LOG.warn("Attempted to disable plugin controller '" + pluginClassName + "', but it could not be found.");
					return;
				}
				
				this.remove(controller.getTab(this));
				
				controller.deinit();
				this.pluginManager.unloadPluginController(controller);
			}
			
			// Finally, change the value in the plugin properties file.
			PluginProperties.getInstance().setPluginEnabled(pluginClassName, enabled);
			PluginProperties.getInstance().saveToDisk();
		}
	}
	
	/**
	 * Adds the text resources for a {@link PluginController} to {@link UiGeneratorController}'s text resource manager.
	 * @param controller the plugin controller whose text resource should be loaded
	 */
	private void addPluginTextResources(PluginController controller) {
		// Add to the default English bundle
		InternationalisationUtils.mergeMaps(Thinlet.DEFAULT_ENGLISH_BUNDLE, controller.getDefaultTextResource());
		
		// Add to the current language bundle
		LanguageBundle currentResourceBundle = FrontlineUI.currentResourceBundle;
		if(currentResourceBundle != null) {
			InternationalisationUtils.mergeMaps(currentResourceBundle.getProperties(), controller.getTextResource(currentResourceBundle.getLocale()));
			setResourceBundle(currentResourceBundle.getProperties(), currentResourceBundle.isRightToLeft());
		}
	}
	
	/** Pass through to method in the {@link HomeTabHandler}. */
	public void showHomeTabSettings() {
		this.homeTabController.showHomeTabSettings();
	}

	/**
	 * Gets the string to display for the recipient of a message.
	 * @param message The message whose recipient to get the name of
	 * @return This will be the name of the contact who received the message, or the recipient's phone number if they are not a contact.
	 */
	public String getRecipientDisplayValue(Message message) {
		Contact recipient = contactDao.getFromMsisdn(message.getRecipientMsisdn());
		String recipientDisplayName = recipient != null ? recipient.getDisplayName() : message.getRecipientMsisdn();
		return recipientDisplayName;
	}

	/**
	 * Gets the string to display for the sender of a message.
	 * @param message The message whose sender to get the name of
	 * @return This will be the name of the contact who sent the message, or the sender's phone number if they are not a contact.
	 * @deprecated should be moved to message tab cont
	 */
	public String getSenderDisplayValue(Message message) {
		Contact sender = contactDao.getFromMsisdn(message.getSenderMsisdn());
		String senderDisplayName = sender != null ? sender.getDisplayName() : message.getSenderMsisdn();
		return senderDisplayName;
	}
	
	/**
	 * Checks if the supplied group is a real group, or just one of the default groups
	 * used for visualization.
	 * @param group the group to check
	 * @return <code>true</code> if the supplied {@link Group} is one of the synthetic groups; <code>false</code> otherwise. 
	 */
	public boolean isDefaultGroup(Group group) {
		return group == this.rootGroup || group == this.ungroupedContacts || group == this.unnamedContacts;
	}

	public void addDatePanel(Object dialog) {
		Object datePanel = loadComponentFromFile(UI_FILE_DATE_PANEL);
		//Adds to the end of the panel, before the button
		add(dialog, datePanel, getItems(dialog).length - 2);
	}
	
	/**
	 * Selects the supplied object. If not found, none is selected.
	 * @param selected
	 * @param groupListComponent
	 */
	public void setSelectedGC(Object selected, Object groupListComponent) {
		for (Object o : getItems(groupListComponent)) {
			if (selected != null) {
				if (isAttachment(selected, Group.class) && isAttachment(o, Group.class)) {
					Group sel = getGroup(selected);
					Group oo = getGroup(o);
					if (oo.getName().equals(sel.getName())) {
						setSelected(o, true);
						return;
					}
				}
				if (isAttachment(selected, Contact.class) && isAttachment(o, Contact.class)) {
					Contact sel = getContact(selected);
					Contact oo = getContact(o);
					if (oo.getName().equals(sel.getName())) {
						setSelected(o, true);
						return;
					}
				}
			}
			setSelectedGC(selected, o);
		}
	}

	public void getGroupsRecursivelyUp(List<Group> groups, Group g) {
		groups.add(g);
		Group parent = g.getParent();
		if (!parent.equals(this.rootGroup)) {
			getGroupsRecursivelyUp(groups, parent);
		}
	}
	
	/**
	 * Shows the message history for the selected contact or group.
	 * @param component group list or contact list
	 */
	public void showMessageHistory(Object component) {
		changeTab(TAB_MESSAGE_HISTORY);
		this.messageTabController.doShowMessageHistory(component);
	}
	
	/** If the confirmation dialog exists, remove it */
	public void removeConfirmationDialog() {
		Object confirm = find(COMPONENT_CONFIRM_DIALOG);
		if (confirm != null) removeDialog(confirm);
	}
	
	/** 
	 * Shows a general dialog asking the user to confirm his action. 
	 * @param methodToBeCalled the name of the method to be called
	 */
	public void showConfirmationDialog(String methodToBeCalled){
		showConfirmationDialog(methodToBeCalled, this);
	}
	
	/** Shows a general dialog asking the user to confirm his action. 
	 * @param methodToBeCalled The name of the method to be called
	 * @param handler The event handler to call the method on
	 */
	public void showConfirmationDialog(String methodToBeCalled, ThinletUiEventHandler handler){
		Object conf = loadComponentFromFile(UI_FILE_CONFIRMATION_DIALOG_FORM);
		setMethod(find(conf, COMPONENT_BT_CONTINUE), ATTRIBUTE_ACTION, methodToBeCalled, conf, handler);
		add(conf);
	}
	
	/** Shows a general dialog asking the user to confirm his action. */
	public void showConfirmationDialog(String methodToBeCalled, Object handler, String confirmationMessageKey) {
		Object conf = loadComponentFromFile(UI_FILE_CONFIRMATION_DIALOG_FORM);
		setMethod(find(conf, COMPONENT_BT_CONTINUE), ATTRIBUTE_ACTION, methodToBeCalled, conf, handler);
		setText(find(conf, "lbText"), InternationalisationUtils.getI18NString(confirmationMessageKey));
		add(conf);
	}
	
	/**
	 * Shows the export wizard dialog, according to the supplied type.
	 * @param list The list to get selected items from.
	 * @param type The desired type
	 */
	public void showExportWizard(Object list, String type){
		new ImportExportUiController(this, this.contactDao, this.messageFactory, this.keywordDao).showWizard(true, list, type);
	}
	
	/**
	 * Shows the export wizard dialog, according to the supplied type.
	 * @param type The desired type
	 */
	public void showExportWizard(String type){
		new ImportExportUiController(this, this.contactDao, this.messageFactory, this.keywordDao).showWizard(true, type);
	}
	
	/**
	 * Shows the import wizard dialog, according to the supplied type.
	 * @param list The list to get selected items from.
	 * @param type The desired type
	 */
	public void showImportWizard(Object list, String type){
		new ImportExportUiController(this, this.contactDao, this.messageFactory, this.keywordDao).showWizard(false, list, type);
	}
	
	/**
	 * Shows the import wizard dialog, according to the supplied type.
	 * @param type The desired type (0 for Contacts, 1 for Messages and 2 for Keywords)
	 */
	public void showImportWizard(String type){
		new ImportExportUiController(this, this.contactDao, this.messageFactory, this.keywordDao).showWizard(false, type);
	}

	/**
	 * Shows the pending message dialog.
	 * @param messages thy messages which are pending
	 */
	private void showPendingMessages(Collection<Message> messages) {
		Object pendingMsgForm = loadComponentFromFile(UI_FILE_PENDING_MESSAGES_FORM);
		Object list = find(pendingMsgForm, COMPONENT_PENDING_LIST);
		for (Message m : messages) {
			add(list, getRowForPending(m));
		}
		add(pendingMsgForm);
	}

	/**
	 * Remove the selected items from the supplied list.
	 * @param recipientList
	 * @param dialog 
	 */
	public void removeSelectedFromRecipientList(Object recipientList, Object dialog) {
		for (Object selected : getSelectedItems(recipientList)) {
			numberToSend--;
			remove(selected);
		}
	}
	
	public void show_composeMessageForm(Group group) {
		LOG.debug("Getting contacts from Group [" + group.getName() + "]");
		
		HashSet<Object> recipients = new HashSet<Object>();
		boolean hasMembers = false;
		for (Contact c : groupMembershipDao.getMembers(group)) {
			hasMembers = true;
			if (c.isActive()) {
				LOG.debug("Adding contact [" + c.getName() + "] to the send list.");
				recipients.add(c);
			}
		}
		
		if (recipients.size() == 0) {
			LOG.debug("No contacts to send, or selected groups contain only dormants.");
			String key = hasMembers ? MESSAGE_ONLY_DORMANTS : MESSAGE_GROUP_NO_MEMBERS;
			alert(InternationalisationUtils.getI18NString(key));
			LOG.trace("EXIT");
		} else {
			show_composeMessageForm(recipients);
		}
	}

	public void show_composeMessageForm(Collection<Object> recipients) {
		numberToSend = recipients.size();
		
		Object dialog = loadComponentFromFile(UI_FILE_COMPOSE_MESSAGE_FORM);
		Object to = find(dialog, COMPONENT_COMPOSE_MESSAGE_RECIPIENT_LIST);

		for(Object recipient : recipients) {
			if(recipient instanceof Contact) {
				add(to, createListItem((Contact)recipient));
			} else if(recipient instanceof String) {
				// FIXME set a suitable icon for this phone number list item
				add(to, createListItem((String)recipient, recipient));
			}
		}
		
		MessagePanelHandler messagePanelController = new MessagePanelHandler(this);
		// We need to add the message panel to the dialog before setting the send button method
		add(dialog, messagePanelController.getPanel());
		messagePanelController.setSendButtonMethod(this, dialog, "sendMessage(composeMessageDialog, composeMessage_to, tfMessage)");
		add(dialog);
		
		LOG.trace("EXIT");
	}
	
	/**
	 * Shows the compose message dialog, populating the list with the selection of the 
	 * supplied list.
	 * @param list
	 */
	public void show_composeMessageForm(Object list) {
		LOG.trace("ENTER");
		// Build up a list of selected recipients, and then pass this to
		// the message composition form.
		for (Object selectedComponent : getSelectedItems(list)) {
			Object attachedItem = getAttachedObject(selectedComponent);
			
			if(attachedItem == null) {
				/** skip null items TODO is this necessary with instanceof */
			} else if (attachedItem instanceof Contact) {
				Set<Object> recipients = new HashSet<Object>();
				Contact contact = (Contact)attachedItem;
				LOG.debug("Adding contact [" + contact.getName() + "] to the send list.");
				recipients.add(getContact(selectedComponent));
				show_composeMessageForm(recipients);
			} else if (attachedItem instanceof Group) {
				show_composeMessageForm((Group) attachedItem);
			} else if (attachedItem instanceof Message) {
				Set<Object> recipients = new HashSet<Object>();
				Message message = (Message) attachedItem;
				// We should only attempt to reply to messages we have received - otherwise
				// we could end up texting ourselves a lot!
				if(message.getType() == Message.TYPE_RECEIVED) {
					String senderMsisdn = message.getSenderMsisdn();
					Contact contact = contactDao.getFromMsisdn(senderMsisdn);
					if(contact != null) {
						recipients.add(contact);
					} else {
						recipients.add(senderMsisdn);
					}
				}
				show_composeMessageForm(recipients);
			}
		}
	}

	/**
	 * This method sends a message for all contacts in the supplied list.
	 * 
	 * @param composeMessageDialog
	 * @param recipientList The list with all contacts.
	 * @param messageContent The desired message.
	 */
	public void sendMessage(Object composeMessageDialog, Object recipientList, Object messageContent) {
		String messageText = getText(messageContent);
		for (Object o : getItems(recipientList)) {
			Object attachedObject = getAttachedObject(o);
			if(attachedObject == null) {
				// Do nothing
				// TODO check this is necessary
			} else if(attachedObject instanceof Contact) {
				Contact c = (Contact)attachedObject;
				frontlineController.sendTextMessage(c.getPhoneNumber(), messageText);
			} else if(attachedObject instanceof String) {
				// Attached object is a phone number
				frontlineController.sendTextMessage((String)attachedObject, messageText);
			}
		}
		remove(composeMessageDialog);
	}

	/**
	 * Refresh the list of phones on {@link UiGeneratorControllerConstants#TAB_ADVANCED_PHONE_MANAGER} .
	 */
	void refreshPhonesViews() {
		// Looks like we don't bother refreshing if the phone list isn't in view
		if (currentTab.equals(TAB_ADVANCED_PHONE_MANAGER)) {
			LOG.debug("Refreshing phones tab");
			this.phoneTabController.refresh();
		}
	}

	/**
	 * Method called when the user first click on the end date textfield and the value is set to undefined.
	 * 
	 * @param o
	 */
	public void removeUndefinedString(Object o) {
		if (getText(o).equals(InternationalisationUtils.getI18NString(COMMON_UNDEFINED))) {
			setText(o, "");
		}
	}

	/**
	 * Updates the status bar with the supplied string.
	 * @param status the new status to display
	 */
	public synchronized void setStatus(String status) {
		LOG.debug("Status Text [" + status + "]");
		setString(statusBarComponent, TEXT, status);
	}
	
	/**
	 * Sets the phone number of the selected contact.
	 * 
	 * @param contactSelecter_contactList
	 * @param dialog
	 */
	public void smsHttpSettings_setTextfield(Object contactSelecter_contactList, Object dialog) {
		Object obj = find("smsHttpDialog");
		Object tf = find(obj, "tfAccountSender");
		Object selectedItem = getSelectedItem(contactSelecter_contactList);
		if (selectedItem == null) {
			alert(InternationalisationUtils.getI18NString(MESSAGE_NO_CONTACT_SELECTED));
			return;
		}
		Contact selectedContact = getContact(selectedItem);
		setText(tf, selectedContact.getPhoneNumber());
		remove(dialog);
	}
	
	/**
	 * Sets the phone number of the selected contact.
	 * 
	 * @param contactSelecter_contactList
	 * @param dialog
	 */
	public void smsHttpSettingsEdit_setTextfield(Object contactSelecter_contactList, Object dialog) {
		Object obj = find("editSmsHttp");
		Object tf = find(obj, "tfAccountSender");
		Object selectedItem = getSelectedItem(contactSelecter_contactList);
		if (selectedItem == null) {
			alert(InternationalisationUtils.getI18NString(MESSAGE_NO_CONTACT_SELECTED));
			return;
		}
		Contact selectedContact = getContact(selectedItem);
		setText(tf, selectedContact.getPhoneNumber());
		remove(dialog);
	}

	/**
	 * Shows the email accounts settings dialog.
	 */
	public void showEmailAccountsSettings() {
		EmailAccountDialogHandler emailAccountDialogHandler = new EmailAccountDialogHandler(this, this.frontlineController);
		add(emailAccountDialogHandler.getDialog());
	}

	/**
	 * Method called when the user changes the task type.
	 */
	public void taskTypeChanged(Object dialog, Object selected) {
		String name = getString(selected, Thinlet.NAME);
		Object label = find(dialog, COMPONENT_LB_TEXT);
		if (name.equalsIgnoreCase(COMPONENT_RB_HTTP)) {
			//HTTP
			setText(label, InternationalisationUtils.getI18NString(COMMON_URL));
			setIcon(label, Icon.ACTION_HTTP_REQUEST);
		} else {
			//CMD
			setText(label, InternationalisationUtils.getI18NString(COMMON_COMMAND));
			setIcon(label, Icon.ACTION_EXTERNAL_COMMAND);
		}
	}
	
	public void showDateSelecter(Object textField) {
		LOG.trace("ENTER");
		try {
			new DateSelecter(this, textField).showSelecter();
		} catch (IOException e) {
			LOG.error("Error parsing file for dateSelecter", e);
			LOG.trace("EXIT");
			throw new RuntimeException(e);
		}
		LOG.trace("EXIT");
	}
	
	public void replyManager_showDateSelecter(Object panel, String type) {
		LOG.trace("ENTER");
		Object textField = type.equals("s") ? find(panel, COMPONENT_TF_START_DATE) : find(panel, COMPONENT_TF_END_DATE);
		try {
			new DateSelecter(this, textField).showSelecter();
		} catch (IOException e) {
			LOG.error("Error parsing file for dateSelecter", e);
			LOG.trace("EXIT");
			throw new RuntimeException(e);
		}
		LOG.trace("EXIT");
	}

	/**
	 * This method is used to show an export dialog, where the user can select the
	 * desired place to create the export file.
	 */
	public void show_exportDialogForm(Object o) {
		String name = getString(o, Thinlet.NAME);
		Object exportDialog = loadComponentFromFile(UI_FILE_EXPORT_DIALOG_FORM);
		setAttachedObject(exportDialog, name);
		add(exportDialog);
	}
	
	/*
	 * Presumably this should be part of the messaging panel controller 
	 */
	public void updateCost() {
		// TODO everything relying on message cost should be updated when this is changed
		this.messageTabController.updateMessageHistoryCost();
	}

	// FIXME fire this on textfield lostFocus or textfield execution (<return> pressed)
	public void costChanged(String cost) {
		if (cost.length() == 0) this.setCostPerSms(0);
		else {
			try {
				double costPerSMS = (InternationalisationUtils.parseCurrency(cost))/* * Utils.TIMES_TO_INT*/;//FIXME this will likely give some very odd costs - needs adjusting for moving decimal point.
				this.setCostPerSms(costPerSMS);
			} catch (ParseException e) {
				alert("Did not understand currency value: " + cost + ".  Should be of the form: " + InternationalisationUtils.formatCurrency(123456.789)); // TODO i18n
			} 
		}
		updateCost();
	}
	
	/**
	 * Method called when an event is fired and should be added to the event list on the home tab.
	 * @param newEvent New instance of {@link Event} to be added to the list.
	 */
	public void newEvent(Event newEvent) {
		this.homeTabController.newEvent(newEvent);
	}
	
	/**
	 * Method invoked when the status for actions changes.
	 * 
	 * @param panel
	 * @param live
	 */
	public void statusChanged(Object panel, boolean live) {
		Object att = getAttachedObject(panel);
		Object startTextField = find(panel, COMPONENT_TF_START_DATE);
		Object endTextField = find(panel, COMPONENT_TF_END_DATE);
		if (att != null) {
			if (live) {
				setText(startTextField, InternationalisationUtils.getDefaultStartDate());
				if (getString(endTextField, Thinlet.TEXT).equals(InternationalisationUtils.getDefaultStartDate())) {
					setText(endTextField, "");
				}
			} else {
				setText(endTextField, InternationalisationUtils.getDefaultStartDate());
			}
		}
	}

	public boolean hasSomethingToDoBeforeExit() {
		LOG.trace("ENTER");
		saveWindowSize();
		boolean somethingToDo = false;
		
		Collection<Message> pending = messageFactory.getMessages(Message.TYPE_OUTBOUND, new Integer[] {Message.STATUS_PENDING});
		if(LOG.isDebugEnabled()) LOG.debug("Pending Messages size [" + pending.size() + "]");
		if (pending.size() > 0) {
			showPendingMessages(pending);
			somethingToDo = true;
		}
		if(LOG.isTraceEnabled()) LOG.trace("EXIT : " + somethingToDo);
		return somethingToDo;
	}
	
	public void close() {
		LOG.trace("ENTER");
		Collection<Message> pending = messageFactory.getMessages(Message.TYPE_OUTBOUND, new Integer[] {Message.STATUS_PENDING});
		LOG.debug("Pending Messages size [" + pending.size() + "]");
		if (pending.size() > 0) {
			showPendingMessages(pending);
		} else {
			exit();
		}
		LOG.trace("EXIT");
	}
	
	@Override
	public boolean destroy() {
		super.destroy();
		return false;
	}
	
	/**
	 * Method called when the user make the final decision to close the app.
	 */
	public void exit() {
		for (Message m : messageFactory.getMessages(Message.TYPE_OUTBOUND, new Integer[] {Message.STATUS_PENDING})) {
			m.setStatus(Message.STATUS_OUTBOX);
		}
		saveWindowSize();
		frameLauncher.dispose();
		this.frontlineController.destroy();
	}

	/**
	 * Writes to the property file the current window size.
	 */
	private void saveWindowSize() {
		UiProperties uiProperties = UiProperties.getInstance();
		uiProperties.setWindowState(frameLauncher.getExtendedState() == Frame.MAXIMIZED_BOTH,
				frameLauncher.getBounds().width, frameLauncher.getBounds().height);
		uiProperties.saveToDisk();
	}
	
	/**
	 * Checks if the object attached to a component is of a specific class.
	 * @param component
	 * @param clazz
	 * @return
	 */
	public boolean isAttachment(Object component, Class<?> clazz) {
		Object object = getAttachedObject(component);
		return object != null && object.getClass().equals(clazz);
	}
	
	/**
	 * Gets the Message instance attached to the supplied component.
	 * 
	 * @param component
	 * @return The Message instance.
	 */
	public Message getMessage(Object component) {
		return (Message) getAttachedObject(component);
	}

	/**
	 * Gets the EMail instance attached to the supplied component.
	 * 
	 * @param component
	 * @return The Email instance.
	 */
	public Email getEmail(Object component) {
		return (Email) getAttachedObject(component);
	}
	
	/**
	 * Gets the Contact instance attached to the supplied component.
	 * 
	 * @param component
	 * @return The Contact instance.
	 */
	public Contact getContact(Object component) {
		return (Contact) getAttachedObject(component);
	}
	
	/**
	 * Gets the Group instance attached to the supplied component.
	 * 
	 * @param component
	 * @return The Group instance.
	 */
	public Group getGroup(Object component) {
		return (Group) getAttachedObject(component);
	}

	/**
	 * Returns the keyword attached to the supplied component.
	 * 
	 * @param component
	 * @return
	 */
	public Keyword getKeyword(Object component) {
		Object obj = getAttachedObject(component);
		if (obj instanceof Keyword) return (Keyword)obj;
		else if (obj instanceof KeywordAction) return ((KeywordAction)obj).getKeyword();
		else if (obj == null) return null;
		else throw new RuntimeException();
	}
	/**
	 * Returns the keyword action attached to the supplied component.
	 * 
	 * @param component
	 * @return
	 */
	public KeywordAction getKeywordAction(Object component) {
		Object obj = getAttachedObject(component);
		if (obj == null) return null;
		else if (obj instanceof KeywordAction) {
			return (KeywordAction)obj;	
		} else throw new RuntimeException();
	}

//	/**
//	 * Get's the group from the selected node of the groups list
//	 * @param selected
//	 * @return
//	 */
//	public Group getGroupFromSelectedNode(Object selected) {
//		while (selected != null && !isAttachment(selected, Group.class)) selected = getParent(selected);
//		if (selected == null) return null;
//		return getGroup(selected);
//	}

//	/**
//	 * Creates a node for the supplied group, creating nodes for its sub-groups and contacts as well.
//	 * 
//	 * @param group The group to be put into a node.
//	 * @param showContactsNumber set <code>true</code> to show the number of contacts per group in the node's text or <code>false</code> otherwise
//	 *   TODO removing this argument, and treating it as always <code>false</code> speeds up the contact tab a lot
//	 * @return
//	 */
//	public Object getNode(Group group, boolean showContactsNumber) {
//		LOG.trace("ENTER");
//		
//		LOG.debug("Group [" + group.getName() + "]");
//		
//		String toSet = group.getName();
//		if (showContactsNumber) {
//			toSet += " (" + group.getAllMembers().size() + ")";
//		}
//		
//		Object node = createNode(toSet, group);
//
//		if ((getBoolean(node, EXPANDED) && group.hasDescendants()) || group == this.rootGroup) {
//			setIcon(node, Icon.FOLDER_OPEN);
//		} else {
//			setIcon(node, Icon.FOLDER_CLOSED);
//		}
//		
//		if (group.equals(this.unnamedContacts)) {
//			setString(node, TOOLTIP, InternationalisationUtils.getI18NString(TOOLTIP_UNNAMED_GROUP));
//		} else if(group.equals(this.ungroupedContacts)) {
//			setString(node, TOOLTIP, InternationalisationUtils.getI18NString(TOOLTIP_UNGROUPED_GROUP));
//		} 
//		
//		if (group == rootGroup) {
//			add(node, getNode(this.ungroupedContacts, showContactsNumber));
//			add(node, getNode(this.unnamedContacts, showContactsNumber));
//		}
//		
//		// Add subgroup components to this node
//		for (Group subGroup : group.getDirectSubGroups()) {
//			Object groupNode = getNode(subGroup, showContactsNumber);
//			add(node, groupNode);
//		}
//		LOG.trace("EXIT");
//		return node;
//	}
	
	/**
	 * Get the status of a {@link Message} as a {@link String}.
	 * @param message
	 * @return {@link String} representation of the status.
	 */
	public static final String getMessageStatusAsString(Message message) {
		switch(message.getStatus()) {
			case Message.STATUS_DRAFT:
				return "(draft)";
			case Message.STATUS_RECEIVED:
				return InternationalisationUtils.getI18NString(COMMON_RECEIVED);
			case Message.STATUS_OUTBOX:
				return InternationalisationUtils.getI18NString(COMMON_OUTBOX);
			case Message.STATUS_PENDING:
				return InternationalisationUtils.getI18NString(COMMON_PENDING);
			case Message.STATUS_SENT:
				return InternationalisationUtils.getI18NString(COMMON_SENT);
			case Message.STATUS_DELIVERED:
				return InternationalisationUtils.getI18NString(COMMON_DELIVERED);
			case Message.STATUS_KEEP_TRYING:
				return InternationalisationUtils.getI18NString(COMMON_RETRYING);
			case Message.STATUS_ABORTED:
				return "(aborted)";
			case Message.STATUS_FAILED:
				return InternationalisationUtils.getI18NString(COMMON_FAILED);
			case Message.STATUS_UNKNOWN:
			default:
				return "(unknown)";
		}
	}
	
	/**
	 * Get the status of a {@link Email} as a {@link String}.
	 * @param email
	 * @return {@link String} representation of the status.
	 */
	public static final String getEmailStatusAsString(Email email) {
		switch(email.getStatus()) {
		case Email.STATUS_OUTBOX:
			return InternationalisationUtils.getI18NString(COMMON_OUTBOX);
		case Email.STATUS_PENDING:
			return InternationalisationUtils.getI18NString(COMMON_PENDING);
		case Email.STATUS_SENT:
			return InternationalisationUtils.getI18NString(COMMON_SENT);
		case Email.STATUS_RETRYING:
			return InternationalisationUtils.getI18NString(COMMON_RETRYING);
		case Email.STATUS_FAILED:
			return InternationalisationUtils.getI18NString(COMMON_FAILED);
		default:
			return "(unknown)";
		}
	}

	/**
	 * Gets a short description of a keyword-action.
	 * @param action The keyword action to get the description.
	 * @return The description of the supplied keyword action.
	 */
	private String getActionDescription(KeywordAction action) {
		StringBuilder ret = new StringBuilder("");
		switch (action.getType()) {
			case KeywordAction.TYPE_FORWARD:
				ret.append(InternationalisationUtils.getI18NString(ACTION_FORWARD));
				ret.append(": \"");
				ret.append(KeywordAction.KeywordUtils.getForwardText(action, DEMO_SENDER, DEMO_SENDER.getPhoneNumber(), action.getKeyword().getKeyword() +  DEMO_MESSAGE_TEXT_INCOMING));
				ret.append("\" ");
				ret.append(InternationalisationUtils.getI18NString(COMMON_TO_GROUP));
				ret.append(": \"");
				ret.append(action.getGroup().getName());
				ret.append("\"");
				break;
			case KeywordAction.TYPE_JOIN:
				ret.append(InternationalisationUtils.getI18NString(COMMON_JOIN));
				ret.append(": ");
				ret.append(action.getGroup().getName());
				break;
			case KeywordAction.TYPE_LEAVE:
				ret.append(InternationalisationUtils.getI18NString(COMMON_LEAVE));
				ret.append(": ");
				ret.append(action.getGroup().getName());
				break;
			case KeywordAction.TYPE_REPLY:
				ret.append(InternationalisationUtils.getI18NString(COMMON_REPLY));
				ret.append(": ");
				ret.append(KeywordAction.KeywordUtils.getReplyText(action, DEMO_SENDER, DEMO_SENDER.getPhoneNumber(), DEMO_MESSAGE_TEXT_INCOMING, DEMO_MESSAGE_KEYWORD));
				break;
			case KeywordAction.TYPE_EXTERNAL_CMD:
				if (action.getExternalCommandType() == KeywordAction.EXTERNAL_HTTP_REQUEST) {
					ret.append(InternationalisationUtils.getI18NString(COMMON_HTTP_REQUEST));
				} else {
					ret.append(InternationalisationUtils.getI18NString(COMMON_EXTERNAL_COMMAND));
				}
				break;
			case KeywordAction.TYPE_EMAIL:
				ret.append(InternationalisationUtils.getI18NString(COMMON_E_MAIL));
				ret.append(": ");
				ret.append(KeywordAction.KeywordUtils.getReplyText(action, DEMO_SENDER, DEMO_SENDER.getPhoneNumber(), action.getKeyword().getKeyword() + DEMO_MESSAGE_TEXT_INCOMING, DEMO_MESSAGE_KEYWORD));
				break;
		}
		return ret.toString();
	}

	public void table_addCells(Object tableRow, String[] cellContents) {
		for(String s : cellContents) add(tableRow, createTableCell(s));
	}

	/**
	 * Creates a list item Thinlet UI Component for the supplied keyword.  The keyword
	 * object is attached to the component, and the component's icon is set appropriately.
	 * @param keyword
	 * @return
	 */
	public Object createListItem(Keyword keyword) {
		String key = keyword.getKeyword().length() == 0 ? "<" + InternationalisationUtils.getI18NString(COMMON_BLANK) + ">" : keyword.getKeyword();
		Object listItem = createListItem(
				key,
				keyword);
		setIcon(listItem, Icon.KEYWORD);
		return listItem;
	}

	/**
	 * Creates a list item Thinlet UI Component for the supplied contact.  The contact
	 * object is attached to the component, and the component's icon is set appropriately.
	 * @param contact
	 * @return
	 */
	public Object createListItem(Contact contact) {
		Object listItem = createListItem(contact.getName() + " (" + contact.getPhoneNumber() + ")", contact);
		setIcon(listItem, Icon.CONTACT);
		return listItem;
	}
	
	/**
	 * Creates a Thinlet UI table row containing details of a contact.
	 * @param contact
	 * @return
	 */
	public Object getRow(Contact contact) {
		Object row = createTableRow(contact);
		
		Object cell = createTableCell("");
		if (contact.isActive()) {
			setIcon(cell, Icon.TICK);
		} else {
			setIcon(cell, Icon.CANCEL);
		}
		setChoice(cell, ALIGNMENT, CENTER);
		add(row, cell);
		
		String name = contact.getName();
		add(row, createTableCell(name));

		add(row, createTableCell(contact.getPhoneNumber()));
		add(row, createTableCell(contact.getEmailAddress()));
		String groups = Utils.contactGroupsAsString(this.groupMembershipDao.getGroups(contact), DEFAULT_GROUPS_DELIMITER);
		add(row, createTableCell(groups));
		return row;
	}

	/**
	 * Creates a Thinlet UI table row containing details of a keyword action.
	 * @param contact
	 * @return
	 */
	public Object getRow(KeywordAction action) {
		Object row = createTableRow(action);
		String icon;
		switch(action.getType()) {
		case KeywordAction.TYPE_FORWARD:
			icon = Icon.ACTION_FORWARD;
			break;
		case KeywordAction.TYPE_JOIN:
			icon = Icon.ACTION_JOIN;
			break;
		case KeywordAction.TYPE_LEAVE:
			icon = Icon.ACTION_LEAVE;
			break;
		case KeywordAction.TYPE_REPLY:
			icon = Icon.ACTION_REPLY;
			break;
		case KeywordAction.TYPE_EXTERNAL_CMD:
			if (action.getExternalCommandType() == KeywordAction.EXTERNAL_COMMAND_LINE) 
				icon = Icon.ACTION_EXTERNAL_COMMAND;
			else 
				icon = Icon.ACTION_HTTP_REQUEST;
			break;
		case KeywordAction.TYPE_EMAIL:
			icon = Icon.ACTION_EMAIL;
			break;
		default:
			icon = Icon.SURVEY;
		break;
		}
		
		Object cell = createTableCell("");
		setIcon(cell, icon);
		add(row, cell);
		add(row, createTableCell(getActionDescription(action)));
		add(row, createTableCell(InternationalisationUtils.getDateFormat().format(action.getStartDate())));
		if (action.getEndDate() != DEFAULT_END_DATE) {
			add(row, createTableCell(InternationalisationUtils.getDateFormat().format(action.getEndDate())));
		} else {
			add(row, createTableCell(InternationalisationUtils.getI18NString(COMMON_UNDEFINED)));
		}
		cell = createTableCell("");
		setIcon(cell, action.isAlive(System.currentTimeMillis()) ? Icon.TICK : Icon.CANCEL);
		setChoice(cell, ALIGNMENT, CENTER);
		add(row, cell);
		add(row, createTableCell(action.getCounter()));
		return row;
	}

	/**
	 * Creates a Thinlet UI table row containing details of an SMS message.
	 * @param message
	 * @return
	 */
	public Object getRow(Message message) {
		Object row = createTableRow(message);

		String icon;
		if (message.getType() == Message.TYPE_RECEIVED) {
			icon = Icon.SMS_RECEIVE;
		} else {
			icon = Icon.SMS_SEND;
		}

		Object iconCell = createTableCell("");
		setIcon(iconCell, icon);
		add(row, iconCell);
		add(row, createTableCell(getMessageStatusAsString(message)));
		add(row, createTableCell(InternationalisationUtils.getDatetimeFormat().format(message.getDate())));
		add(row, createTableCell(message.getSenderMsisdn()));
		add(row, createTableCell(message.getRecipientMsisdn()));
		add(row, createTableCell(message.getTextContent()));
		return row;
	}

	/**
	 * Creates a Thinlet UI table row containing details of an e-mail account.
	 * @param message
	 * @return
	 */
	public Object getRow(EmailAccount acc) {
		Object row = createTableRow(acc);

		Object iconCell = createTableCell("");
		setIcon(iconCell, acc.useSsl() ? Icon.SSL : Icon.NO_SSL);
		add(row, iconCell);
		add(row, createTableCell(acc.getAccountServer()));
		add(row, createTableCell(acc.getAccountName()));
		
		return row;
	}
	
	/**
	 * Creates a Thinlet UI table row containing details of an SMS message.
	 * @param message
	 * @return
	 */
	private Object getRowForPending(Message message) {
		Object row = createTableRow(message);

		String senderDisplayName = getSenderDisplayValue(message);
		String recipientDisplayName = getRecipientDisplayValue(message);
		
		add(row, createTableCell(senderDisplayName));
		add(row, createTableCell(recipientDisplayName));
		add(row, createTableCell(message.getTextContent()));

		return row;
	}
	
	/**
	 * Creates a Thinlet UI table row containing details of an Email.
	 * @param email
	 * @return
	 */
	public Object getRow(Email email) {
		Object row = createTableRow(email);

		add(row, createTableCell(getEmailStatusAsString(email)));
		if (email.getDate() == DEFAULT_END_DATE) {
			add(row, createTableCell(""));
		} else {
			add(row, createTableCell(InternationalisationUtils.getDatetimeFormat().format(email.getDate())));
		}
		add(row, createTableCell(email.getEmailFrom().getAccountName()));
		add(row, createTableCell(email.getEmailRecipients()));
		add(row, createTableCell(email.getEmailSubject()));
		add(row, createTableCell(email.getEmailContent()));

		return row;
	}
	
	/**
	 * Called when the current tab is changed; handles the tab-specific refreshments that need to be made.
	 * @param tabbedPane
	 */
	public void tabSelectionChanged(Object tabbedPane) {
		LOG.trace("ENTER");
		Object newTab = getSelectedItem(tabbedPane);
		currentTab = getString(newTab, NAME);
		LOG.debug("Current tab [" + currentTab + "]");
		if (currentTab == null) return;
		if (currentTab.equals(TAB_CONTACT_MANAGER)) {
			this.contactsTabController.refresh();
			setStatus(InternationalisationUtils.getI18NString(MESSAGE_CONTACT_MANAGER_LOADED));
		} else if (currentTab.equals(TAB_ADVANCED_PHONE_MANAGER)) {
			this.phoneTabController.refresh();
			setStatus(InternationalisationUtils.getI18NString(MESSAGE_MODEM_LIST_UPDATED));
		} else if (currentTab.equals(TAB_MESSAGE_HISTORY)) {
			this.messageTabController.refresh();
			setStatus(InternationalisationUtils.getI18NString(MESSAGE_MESSAGES_LOADED));
		} else if (currentTab.equals(TAB_KEYWORD_MANAGER)) {
			this.keywordTabHandler.refresh();
			setStatus(InternationalisationUtils.getI18NString(MESSAGE_KEYWORDS_LOADED));
		} else if (currentTab.equals(TAB_EMAIL_LOG)) {
			this.emailTabHandler.refresh();
			setStatus(InternationalisationUtils.getI18NString(MESSAGE_EMAILS_LOADED));
		}
		LOG.trace("EXIT");
	}

	public synchronized void outgoingEmailEvent(EmailSender sender, Email email) {
		LOG.trace("ENTER");
		LOG.debug("E-mail [" + email.getEmailContent() + "], Status [" + email.getStatus() + "]");
		if (currentTab.equals(TAB_EMAIL_LOG)) {
			this.emailTabHandler.outgoingEmailEvent(sender, email);
		}
		if (email.getStatus() == Email.STATUS_SENT) {
			newEvent(new Event(Event.TYPE_OUTGOING_EMAIL, InternationalisationUtils.getI18NString(COMMON_E_MAIL) + ": " + email.getEmailContent()));
		}
		LOG.trace("EXIT");
	}
	
	/**
	 * Event triggered when a contact is added to a group by a {@link KeywordAction}.
	 */
	public synchronized void contactAddedToGroup(Contact contact, Group group) {
		if(currentTab.equals(TAB_CONTACT_MANAGER)) {
			this.contactsTabController.addToContactList(contact, group);
		}
	}

	public synchronized void keywordActionExecuted(KeywordAction action) {
		if (currentTab.equals(TAB_KEYWORD_MANAGER)) {
			Object keyTab = find(currentTab);
			Object pnKeywordActionsAdvanced = find(keyTab, COMPONENT_PN_KEYWORD_ACTIONS_ADVANCED);
			if (pnKeywordActionsAdvanced != null) {
				Object actionsList = find(pnKeywordActionsAdvanced, COMPONENT_ACTION_LIST);
				int index = -1;
				for (Object act : getItems(actionsList)) {
					KeywordAction a = getKeywordAction(act);
					if (a.equals(action)) {
						index = getIndex(actionsList, act);
						remove(act);
						break;
					}
				}
				if (index != -1) {
					add(actionsList, getRow(action), index);
				}
			}
		}
	}
	
	public void changeLanguage(Object menuItem) {
		AppProperties appProperties = AppProperties.getInstance();
		appProperties.setLanguageFilename(getAttachedObject(menuItem).toString());
		appProperties.saveToDisk();
		reloadUi();
	}
	
	private void addLanguageMenu(Object menu) {
		for(FileLanguageBundle languageBundle : InternationalisationUtils.getLanguageBundles()) {
			Object menuitem = create(MENUITEM);
			setText(menuitem, languageBundle.getLanguageName());
			setIcon(menuitem, getFlagIcon(languageBundle));
			setMethod(menuitem, ATTRIBUTE_ACTION, "changeLanguage(this)", menu, this);
			
			setAttachedObject(menuitem, languageBundle.getFile().getAbsolutePath());
			add(menu, menuitem);
		}
	}
	
	public void showAboutScreen() {
		Object about = loadComponentFromFile(UI_FILE_ABOUT_PANEL);
		String version = InternationalisationUtils.getI18NString(FrontlineSMSConstants.I18N_APP_VERSION, BuildProperties.getInstance().getVersion());
		setText(find(about, "version"), version);
		add(about);
	}

	public void incomingMessageEvent(Message message) {
		LOG.trace("ENTER");
		if (currentTab.equals(TAB_MESSAGE_HISTORY)) {
			this.messageTabController.incomingMessageEvent(message);
		}
		newEvent(new Event(Event.TYPE_INCOMING_MESSAGE, InternationalisationUtils.getI18NString(COMMON_MESSAGE) + ": " + message.getTextContent()));
		setStatus(InternationalisationUtils.getI18NString(MESSAGE_MESSAGE_RECEIVED));
		LOG.trace("EXIT");
	}

	public void outgoingMessageEvent(Message message) {
		LOG.trace("ENTER");
		LOG.debug("Message [" + message.getTextContent() + "], Status [" + message.getStatus() + "]");
		if (currentTab.equals(TAB_MESSAGE_HISTORY)) {
			this.messageTabController.outgoingMessageEvent(message);
		} 
		if (message.getStatus() == Message.STATUS_SENT) {
			newEvent(new Event(Event.TYPE_OUTGOING_MESSAGE, InternationalisationUtils.getI18NString(COMMON_MESSAGE) + ": " + message.getTextContent()));
		} else if (message.getStatus() == Message.STATUS_FAILED) {
			newEvent(new Event(Event.TYPE_OUTGOING_MESSAGE_FAILED, InternationalisationUtils.getI18NString(COMMON_MESSAGE) + ": " + message.getTextContent()));
		}
		LOG.trace("ENTER");
	}
	
	public void switchToPhonesTab() {
		changeTab(TAB_ADVANCED_PHONE_MANAGER);
	}
	
	public void tabsChanged(Object menuItem) {
		Object tabbedPane = find(COMPONENT_TABBED_PANE);
		String name = getName(menuItem);
		boolean selected = isSelected(menuItem);
		String tabName = "";
		if (selected) {
			// Add a tab
			if (name.equals(COMPONENT_MI_HOME)) {
				// add the home tab at index ZERO - the tab furthest on the left
				add(tabbedPane, this.homeTabController.getTab(), 0);
				tabName = "hometab";
			} else if (name.equals(COMPONENT_MI_KEYWORD)) {
				add(tabbedPane, this.keywordTabHandler.getTab());
				tabName = "keywordstab";
			} else if (name.equals(COMPONENT_MI_EMAIL)) {
				add(tabbedPane, this.emailTabHandler.getTab());
				tabName = "emailstab";
			}
		} else {
			Object tab = null;
			// Remove a tab
			if (name.equals(COMPONENT_MI_HOME)) {
				tab = find(tabbedPane, TAB_HOME);
				tabName = "hometab";
			} else if (name.equals(COMPONENT_MI_KEYWORD)) {
				tab = find(tabbedPane, TAB_KEYWORD_MANAGER);
				tabName = "keywordstab";
			} else if (name.equals(COMPONENT_MI_EMAIL)) {
				tab = find(tabbedPane, TAB_EMAIL_LOG);
				tabName = "emailstab";
			}
			if (tab != null) {
				remove(tab);
				setInteger(tabbedPane, SELECTED, 0);
			}
		}
		
		// Save tab visibility to disk
		UiProperties uiProperties = UiProperties.getInstance();
		uiProperties.setTabVisible(tabName, selected);
		uiProperties.saveToDisk();
	}
	
	private void changeTab(String tabName) {
		Object tabbedPane = find(COMPONENT_TABBED_PANE);
		Object tab = find(tabName);
		// The following method is taken from the inside of Thinlet.handleMouseEvent().
		// Had to extend the visibility of a number of methods to make this possible.
		int current = getIndex(tabbedPane, tab);
		setInteger(tabbedPane, SELECTED, current, 0);
		checkOffset(tabbedPane);
		
		tabSelectionChanged(tabbedPane);
		repaint(tabbedPane);
	}
	
	public void showUserDetailsDialog() {
		Object detailsDialog = loadComponentFromFile(UI_FILE_USER_DETAILS_DIALOG);
		add(detailsDialog);
	}
	
	public void reportError(Object dialog) {
		removeDialog(dialog);
		final String userName = getText(find(dialog, "tfName"));
		final String userEmail = getText(find(dialog, "tfEmail"));
		new Thread("ERROR_REPORT") {
			public void run() {
				try {
					ErrorUtils.sendLogs(userName, userEmail, true);
					String success = InternationalisationUtils.getI18NString(MESSAGE_LOG_FILES_SENT);
					LOG.debug(success);
					alert(success);
					setStatus(success);
				} catch (MessagingException e) {
					String msg = InternationalisationUtils.getI18NString(MESSAGE_FAILED_TO_SEND_REPORT);
					LOG.debug(msg, e);
					setStatus(msg);
					alert(msg);
					// Show user the option to save the logs.zip to a place they know!
					String dir = ErrorUtils.showFileChooserForSavingZippedLogs();
					if (dir != null) {
						try {
							ErrorUtils.copyLogsZippedToDir(dir);
						} catch (IOException e1) {
							LOG.debug("", e1);
							String first = InternationalisationUtils.getI18NString(MESSAGE_FAILED_TO_COPY_LOGS);
							String second = InternationalisationUtils.getI18NString(MESSAGE_LOGS_LOCATED_IN);
							setStatus(first.replace(ARG_VALUE, ZIPPED_LOGS_FILENAME) + "." + second.replace(ARG_VALUE, ResourceUtils.getConfigDirectoryPath() + "logs") + ".");
							alert(first.replace(ARG_VALUE, ZIPPED_LOGS_FILENAME) + "." + second.replace(ARG_VALUE, ResourceUtils.getConfigDirectoryPath() + "logs") + ".");
						}
						msg = InternationalisationUtils.getI18NString(MESSAGE_LOGS_SAVED_PLEASE_REPORT);
						setStatus(msg);
						alert(msg);
					}
				} catch (IOException e) {
					// Problem writing logs.zip
					LOG.debug("", e);
					try {
						ErrorUtils.sendToFrontlineSupport(userName, userEmail, null);
					} catch (MessagingException e1) {
						LOG.debug("", e1);
					}
				} finally {
					File input = new File(ResourceUtils.getConfigDirectoryPath() + ZIPPED_LOGS_FILENAME);
					if (input.exists()) {
						input.deleteOnExit();
					}
				}
			}
		}.start();
	}

	/**
	 * Handles expansion changes to a group list - a group's icon changes
	 * depending on whether it is expanded or collapsed and whether it
	 * has subgroups and members or not.
	 * @param groupList
	 */
	public void groupList_expansionChanged(Object groupList) {
		for (Object o : getItems(groupList)) {
			if (isAttachment(o, Group.class)) {
				if(getBoolean(o, EXPANDED) && groupDao.hasDescendants(getAttachedObject(o, Group.class))) {
					// Set the icon to EXPANDED, and set children icons too!
					setIcon(o, Icon.FOLDER_OPEN);
					groupList_expansionChanged(o);
				} else {
					// Set the icon to CLOSED
					setIcon(o, Icon.FOLDER_CLOSED);
				}
			}
		}
	}
	
	/**
	 * Show dialog for editing Forms settings.
	 */
    public void showFormsSettings() {
        Object dialog = loadComponentFromFile("/ui/dialog/formsSettings.xml");
        add(dialog);
    }
    
    /**
     * Show the dialog for viewing and editing settings for {@link SmsInternetServiceSettingsHandler}.
     */
    public void showSmsInternetServiceSettings() {
    	new SmsInternetServiceSettingsHandler(this).showSettingsDialog();
    }
	
	public void showConfigurationLocationDialog() {
		Object dialog = loadComponentFromFile("/ui/dialog/configurationLocation.xml");
		setText(find(dialog, "tfConfigurationLocation"), ResourceUtils.getConfigDirectoryPath());
		add(dialog);
	}

//> ACCESSORS
	/** @return {@link #frontlineController} */
	public FrontlineSMS getFrontlineController() {
		return this.frontlineController;
	}
	/** @return {@link #phoneManager} */
	public SmsDeviceManager getPhoneManager() {
		return this.phoneManager;
	}
	/** @return {@link #phoneDetailsManager} */
	public SmsModemSettingsDao getPhoneDetailsManager() {
		return phoneDetailsManager;
	}
	
	/** @return Cost set per SMS message */
	private double getCostPerSms() {
		return UiProperties.getInstance().getCostPerSms();
	}
	/** @param costPerSMS new value for {@link #costPerSMS} */
	private void setCostPerSms(double costPerSms) {
		UiProperties properties = UiProperties.getInstance();
		properties.setCostPerSms(costPerSms);
		properties.saveToDisk();
	}
	
	/** @return the current tab as an object component */
	public Object getCurrentTab() {
		return this.find(this.currentTab);
	}

	/** @return {@link #rootGroup} */
	public Group getRootGroup() {
		return this.rootGroup;
	}
	
	public Group getUnnamedContacts() {
		return unnamedContacts;
	}
	
	public Group getUngroupedContacts() {
		return ungroupedContacts;
	}

	/** @return the {@link Frame} attached to this thinlet window */
	public Frame getFrameLauncher() {
		return this.frameLauncher;
	}
	
	public SmsInternetServiceSettingsDao getSmsInternetServiceSettingsDao() {
		return frontlineController.getSmsInternetServiceSettingsDao();
	}
	
	public Collection<SmsInternetService> getSmsInternetServices() {
		return this.frontlineController.getSmsInternetServices();
	}
	
	public void addSmsInternetService(SmsInternetService smsInternetService) {
		this.phoneManager.addSmsInternetService(smsInternetService);
	}

	public void contactRemovedFromGroup(Contact contact, Group group) {
		if(this.currentTab.equals(TAB_CONTACT_MANAGER)) {
			// TODO perhaps update the contact manager to remove the contact from the group, if it is currently relevant
		}
	}
	
	public void showDatabaseConfigDialog() {
		DatabaseSettingsPanel databaseSettings = DatabaseSettingsPanel.createNew(this);
		databaseSettings.showAsDialog();
	}
	
	/** Reloads the ui. */
	public final void reloadUi() {
		this.frameLauncher.dispose();
		this.frameLauncher.setContent(null);
		this.frameLauncher = null;
		this.destroy();
		try {
			new UiGeneratorController(frontlineController, false);
		} catch(Throwable t) {
			log.error("Unable to reload frontlineSMS.", t);
		}
	}
	
	/** UI Event method: Generate test data 
	 * @throws IOException */
	public void generateTestData() throws IOException {
		RandomDataGenerator randy = new RandomDataGenerator();
		randy.initFromClasspath();
		randy.setFrontlineController(this.frontlineController);
		randy.generate(200);
	}
	
	public void showGroupSelecter() {
		Object dialog = super.createDialog("Group Selecter Test");
		GroupSelecterPanel selecter = new GroupSelecterPanel(this, this);
		selecter.init(this.groupDao.getChildGroups(null).toArray(new Group[0]));
		add(dialog, selecter.getPanelComponent());
		add(dialog);
	}
	
	public void groupSelectionChanged(Group selectedGroup) {
		System.out.println("GROUP SELECTION IS NOW: " + selectedGroup);
	}
}

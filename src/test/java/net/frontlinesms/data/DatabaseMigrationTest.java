/**
 * 
 */
package net.frontlinesms.data;

import java.util.Arrays;
import java.util.List;

import org.springframework.context.support.ClassPathXmlApplicationContext;

import net.frontlinesms.data.domain.FrontlineMessage;
import net.frontlinesms.data.domain.FrontlineMultimediaMessage;
import net.frontlinesms.data.domain.FrontlineMultimediaMessagePart;
import net.frontlinesms.data.repository.MessageDao;
import net.frontlinesms.junit.BaseTestCase;

/**
 * @author Alex Anderson <alex@frontlinesms.com>
 */
public class DatabaseMigrationTest extends BaseTestCase {
	private static final FrontlineMessage STANDARD_MESSAGE = null;
	private static final FrontlineMessage MM_MESSAGE = null;
	
	private MessageDao dao;
	
	public void testAddingMultimediaMessages() {
		// Initialise the database WITHOUT multimedia messages
		initSpringHibernate(FrontlineMessage.class);
		
		// persist some non-multimedia messages
		dao.saveMessage(STANDARD_MESSAGE);
		
		// retrieve the message
		List<FrontlineMessage> allMessages1 = dao.getAllMessages();
		assertEquals(1, allMessages1.size());
		assertEquals(STANDARD_MESSAGE, allMessages1.get(0));
		
		// deinitialise the database
		deinitSpringHibernate();
		
		// Initiliase the database WITH multimedia messages
		initSpringHibernate(FrontlineMessage.class,
				FrontlineMultimediaMessage.class,
				FrontlineMultimediaMessagePart.class);
		
		// persist some multimedia messages
		dao.saveMessage(MM_MESSAGE);
		
		// retrieve the multimedia messages
		List<FrontlineMessage> allMessages2 = dao.getAllMessages();
		assertEquals(2, allMessages2.size());
		assertEquals(Arrays.asList(STANDARD_MESSAGE, MM_MESSAGE), allMessages2);

		// deinitialise the database
		deinitSpringHibernate();
	}

	/**
	 * Initialise the application context.
	 * @param entityClasses the classes to initialise as JPA entities for persistence and retrieval
	 */
	private void initSpringHibernate(Class<?>... entityClasses) {
		// load application context
		ClassPathXmlApplicationContext app = new ClassPathXmlApplicationContext(getClass().getSimpleName() + "-spring.xml");
		
		// TODO inject database entity list into application context
		
		// refresh application context
		app.refresh();
		
		// update class properties from application context
		this.dao = (MessageDao) app.getBean("messageDao");
	}

	/** Undoes the configuration from {@link #initSpringHibernate(Class...)} */
	private void deinitSpringHibernate() {
		// unset class properties loaded from application context
		this.dao = null;
	}
}

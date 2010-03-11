/**
 * 
 */
package net.frontlinesms.data.repository.hibernate;

import java.util.Collection;
import java.util.List;

import org.hibernate.criterion.Criterion;
import org.hibernate.criterion.DetachedCriteria;
import org.hibernate.criterion.Restrictions;
import org.hibernate.criterion.SimpleExpression;

import net.frontlinesms.data.Order;
import net.frontlinesms.data.domain.Email;
import net.frontlinesms.data.domain.Keyword;
import net.frontlinesms.data.domain.Message;
import net.frontlinesms.data.domain.Message.Field;
import net.frontlinesms.data.domain.Message.Type;
import net.frontlinesms.data.repository.MessageDao;

/**
 * Hibernate implementation of {@link MessageDao}.
 * @author Alex
 */
public class HibernateMessageDao extends BaseHibernateDao<Message> implements MessageDao {
	/** Create instance of this class */
	public HibernateMessageDao() {
		super(Message.class);
	}

	/** @see MessageDao#deleteMessage(Message) */
	public void deleteMessage(Message message) {
		super.delete(message);
	}

	/** @see MessageDao#getAllMessages() */
	public List<Message> getAllMessages() {
		return super.getAll();
	}

	/** @see MessageDao#getAllMessages(int, Field, Order, Long, Long, int, int) */
	public List<Message> getAllMessages(Message.Type messageType, Field sortBy, Order order, Long start, Long end, int startIndex, int limit) {
		DetachedCriteria criteria = super.getSortCriterion(sortBy, order);
		addTypeCriteria(criteria, messageType);
		addDateCriteria(criteria, start, end);
		return super.getList(criteria, startIndex, limit);
	}

	/** @see MessageDao#getMessageCount(int, Integer[]) */
	public int getMessageCount(Message.Type messageType, Integer[] messageStati) {
		DetachedCriteria criteria = DetachedCriteria.forClass(Email.class);
		addStatusCriteria(criteria, messageStati);
		addTypeCriteria(criteria, messageType);
		return getCount(criteria);
	}

	/** @see MessageDao#getMessageCount(int, Long, Long) */
	public int getMessageCount(Message.Type messageType, Long start, Long end) {
		DetachedCriteria criteria = super.getCriterion();
		addDateCriteria(criteria, start, end);
		addTypeCriteria(criteria, messageType);
		return getCount(criteria);
	}

	/** @see MessageDao#getMessageCount(int, Keyword, Long, Long) */
	public int getMessageCount(Message.Type messageType, Keyword keyword, Long start, Long end) {
		DetachedCriteria criteria = super.getCriterion();
		addDateCriteria(criteria, start, end);
		addTypeCriteria(criteria, messageType);
		addKeywordMatchCriteria(criteria, keyword);
		return getCount(criteria);
	}

	/** @see MessageDao#getMessageCountForMsisdn(int, String, Long, Long) */
	public int getMessageCountForMsisdn(Message.Type messageType, String phoneNumber, Long start, Long end) {
		DetachedCriteria criteria = super.getCriterion();
		addTypeCriteria(criteria, messageType);
		addDateCriteria(criteria, start, end);
		addPhoneNumberMatchCriteria(criteria, phoneNumber, true, true);
		return getCount(criteria);
	}

	/** @see MessageDao#getMessageForStatusUpdate(String, int) */
	public Message getMessageForStatusUpdate(String targetMsisdnSuffix, int smscReference) {
		DetachedCriteria criteria = super.getCriterion();
		criteria.add(Restrictions.eq(Field.RECIPIENT_MSISDN.getFieldName(), targetMsisdnSuffix));
		criteria.add(Restrictions.eq(Field.SMSC_REFERENCE.getFieldName(), smscReference));
		return super.getUnique(criteria);
	}

	/** @see MessageDao#getMessages(int, Field, Order) */
	public List<Message> getMessages(Message.Type messageType, Field sortBy, Order order) {
		DetachedCriteria criteria = super.getSortCriterion(sortBy, order);
		addTypeCriteria(criteria, messageType);
		return getList(criteria);
	}

	/** @see MessageDao#getMessagesForKeyword(int, Keyword, Field, Order, Long, Long, int, int) */
	public List<Message> getMessages(Message.Type messageType, Keyword keyword, Field sortBy, Order order) {
		DetachedCriteria criteria = getSortCriterion(sortBy, order);
		addTypeCriteria(criteria, messageType);
		addKeywordMatchCriteria(criteria, keyword);
		return getList(criteria);
	}

	/** @see MessageDao#getMessages(int, Integer[]) */
	public Collection<Message> getMessages(Message.Type messageType, Integer[] status) {
		DetachedCriteria criteria = super.getCriterion();
		addTypeCriteria(criteria, messageType);
		addStatusCriteria(criteria, status);
		return getList(criteria);
	}
	
	public int getMessageCount(Message.Type messageType, List<String> phoneNumbers,
			Long messageHistoryStart, Long messageHistoryEnd) {
		return super.getCount(getCriteria(messageType, phoneNumbers,
				messageHistoryStart, messageHistoryEnd));
	}
	
	public List<Message> getMessages(Message.Type messageType,
			List<String> phoneNumbers, Long messageHistoryStart,
			Long messageHistoryEnd) {
		return super.getList(getCriteria(messageType, phoneNumbers,
				messageHistoryStart, messageHistoryEnd));
	}

	private DetachedCriteria getCriteria(Message.Type messageType,
			List<String> phoneNumbers, Long messageHistoryStart,
			Long messageHistoryEnd) {
		DetachedCriteria criteria = super.getCriterion();
		addTypeCriteria(criteria, messageType);
		addDateCriteria(criteria, messageHistoryStart, messageHistoryEnd);
		addPhoneNumberMatchCriteria(criteria, phoneNumbers, true, true);
		return criteria;
	}

	/** @see MessageDao#getMessagesForKeyword(int, Keyword, Field, Order, Long, Long, int, int) */
	public List<Message> getMessagesForKeyword(Message.Type messageType, Keyword keyword, Field sortBy, Order order, Long start, Long end, int startIndex, int limit) {
		DetachedCriteria criteria = super.getSortCriterion(sortBy, order);
		addTypeCriteria(criteria, messageType);
		addDateCriteria(criteria, start, end);
		addKeywordMatchCriteria(criteria, keyword);
		return getList(criteria);
	}

	/** @see MessageDao#getMessagesForKeyword(int, Keyword) */
	public List<Message> getMessagesForKeyword(Message.Type messageType, Keyword keyword) {
		DetachedCriteria criteria = super.getCriterion();
		addTypeCriteria(criteria, messageType);
		addKeywordMatchCriteria(criteria, keyword);
		return getList(criteria);
	}

	/** @see MessageDao#getMessagesForMsisdn(int, String, Field, Order, Long, Long, int, int) */
	public List<Message> getMessagesForMsisdn(Message.Type messageType, String phoneNumber, Field sortBy, Order order, Long start, Long end, int startIndex, int limit) {
		DetachedCriteria criteria = super.getSortCriterion(sortBy, order);
		addTypeCriteria(criteria, messageType);
		addDateCriteria(criteria, start, end);
		addPhoneNumberMatchCriteria(criteria, phoneNumber, true, true);
		return super.getList(criteria, startIndex, limit);
	}

	/** @see MessageDao#getMessagesForMsisdn(int, String, Field, Order, Long, Long) */
	public List<Message> getMessagesForMsisdn(Message.Type messageType, String phoneNumber, Field sortBy, Order order, Long start, Long end) {
		DetachedCriteria criteria = super.getSortCriterion(sortBy, order);
		addTypeCriteria(criteria, messageType);
		addDateCriteria(criteria, start, end);
		addPhoneNumberMatchCriteria(criteria, phoneNumber, true, true);
		return super.getList(criteria);
	}

	/** @see MessageDao#getMessagesForStati(int, Integer[], Field, Order, int, int) */
	public List<Message> getMessagesForStati(Message.Type messageType, Integer[] messageStati, Field sortBy, Order order, int startIndex, int limit) {
		DetachedCriteria criteria = super.getSortCriterion(sortBy, order);
		addTypeCriteria(criteria, messageType);
		addStatusCriteria(criteria, messageStati);
		return super.getList(criteria, startIndex, limit);
	}

	/** @see MessageDao#getSMSCount(Long, Long) */
	public int getSMSCount(Long start, Long end) {
		DetachedCriteria criteria = super.getCriterion();
		addDateCriteria(criteria, start, end);
		return super.getCount(criteria);
	}

	/** @see MessageDao#getSMSCountForKeyword(Keyword, Long, Long) */
	public int getSMSCountForKeyword(Keyword keyword, Long start, Long end) {
		DetachedCriteria criteria = super.getCriterion();
		addDateCriteria(criteria, start, end);
		addKeywordMatchCriteria(criteria, keyword);
		return super.getCount(criteria);
	}

	/** @see MessageDao#getSMSCountForMsisdn(String, Long, Long) */
	public int getSMSCountForMsisdn(String phoneNumber, Long start, Long end) {
		DetachedCriteria criteria = super.getCriterion();
		addDateCriteria(criteria, start, end);
		addPhoneNumberMatchCriteria(criteria, phoneNumber, true, true);
		return super.getCount(criteria);
	}

	/** @see MessageDao#saveMessage(Message) */
	public void saveMessage(Message message) {
		super.saveWithoutDuplicateHandling(message);
	}

	/** @see MessageDao#updateMessage(Message) */
	public void updateMessage(Message message) {
		super.updateWithoutDuplicateHandling(message);
	}
	
	/**
	 * Augments the supplied criteria with that required to match a keyword.
	 * @param criteria
	 * @param keyword 
	 */
	private void addKeywordMatchCriteria(DetachedCriteria criteria, Keyword keyword) {
		String keywordString = keyword.getKeyword();
		// FIXME this should be case-insensitive
		Criterion matchKeyword = Restrictions.or(
				Restrictions.ilike(Field.MESSAGE_CONTENT.getFieldName(), keywordString), // This should match the keyword exactly, case insensitive
				Restrictions.ilike(Field.MESSAGE_CONTENT.getFieldName(), keywordString + ' '));
		criteria.add(matchKeyword);
	}
	
	/**
	 * Augments the supplied criteria with that required to match an msisdn, either for the sender, the receiver or both.
	 * @param criteria
	 * @param phoneNumber 
	 * @param sender 
	 * @param receiver 
	 */
	private void addPhoneNumberMatchCriteria(DetachedCriteria criteria, String phoneNumber, boolean sender, boolean receiver) {
		if(!sender && !receiver) {
			throw new IllegalStateException("This neither sender nor receiver matching is requested.");
		}
		SimpleExpression eqSender = Restrictions.eq(Field.SENDER_MSISDN.getFieldName(), phoneNumber);
		SimpleExpression eqReceiver = Restrictions.eq(Field.RECIPIENT_MSISDN.getFieldName(), phoneNumber);
		if(sender && receiver) {
			criteria.add(Restrictions.or(eqSender, eqReceiver));
		} else if(sender) {
			criteria.add(eqSender);
		} else if(receiver) {
			criteria.add(eqReceiver);
		}
	}
	
	/**
	 * Augments the supplied criteria with that required to match an msisdn, either for the sender, the receiver or both.
	 * @param criteria
	 * @param phoneNumber 
	 * @param sender 
	 * @param receiver 
	 */
	private void addPhoneNumberMatchCriteria(DetachedCriteria criteria, List<String> phoneNumbers, boolean sender, boolean receiver) {
		if(!sender && !receiver) {
			throw new IllegalStateException("This neither sender nor receiver matching is requested.");
		}

		// build multi level OR
		Criterion ors = null;
		for(String phoneNumber : phoneNumbers) {
			if(sender) {
				SimpleExpression eqSender = Restrictions.eq(Field.SENDER_MSISDN.getFieldName(), phoneNumber);
				if(ors == null) ors = eqSender;
				else ors = Restrictions.or(ors, eqSender);
			}

			if(receiver) {
				SimpleExpression eqReceiver = Restrictions.eq(Field.RECIPIENT_MSISDN.getFieldName(), phoneNumber);
				if(ors == null) ors = eqReceiver;
				else ors = Restrictions.or(ors, eqReceiver);
			}
		}
		
		// If we've made any ORs, apply them to the criteria
		if(ors != null) {
			criteria.add(ors);
		}
	}
	
	/**
	 * Augments the supplied criteria with that required to match a date range.
	 * @param criteria
	 * @param start
	 * @param end
	 */
	private void addDateCriteria(DetachedCriteria criteria, Long start, Long end) {
		if(start != null) {
			criteria.add(Restrictions.ge(Field.DATE.getFieldName(), start));
		}
		if(end != null) {
			criteria.add(Restrictions.le(Field.DATE.getFieldName(), end));
		}
	}
	
	/**
	 * Augments the supplied criteria with that required to match a date range.
	 * @param criteria
	 * @param messageType 
	 */
	private void addTypeCriteria(DetachedCriteria criteria, Message.Type messageType) {
		if(messageType != Type.TYPE_ALL) {
			criteria.add(Restrictions.eq(Field.TYPE.getFieldName(), messageType));
		}
	}

	/**
	 * Augments the supplied criteria with that required to match a date range.
	 * @param criteria 
	 * @param statuses 
	 */
	private void addStatusCriteria(DetachedCriteria criteria, Integer[] statuses) {
		criteria.add(Restrictions.in(Field.STATUS.getFieldName(), statuses));
	}
}

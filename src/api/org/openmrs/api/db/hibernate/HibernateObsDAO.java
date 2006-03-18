package org.openmrs.api.db.hibernate;

import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Vector;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.hibernate.Criteria;
import org.hibernate.Query;
import org.hibernate.Session;
import org.hibernate.criterion.Expression;
import org.openmrs.Concept;
import org.openmrs.Encounter;
import org.openmrs.Location;
import org.openmrs.MimeType;
import org.openmrs.Obs;
import org.openmrs.Patient;
import org.openmrs.api.context.Context;
import org.openmrs.api.db.DAOException;
import org.openmrs.api.db.ObsDAO;
import org.openmrs.util.OpenmrsConstants;

//import org.springframework.orm.hibernate3.support.HibernateDaoSupport;

public class HibernateObsDAO implements ObsDAO {

	protected final Log log = LogFactory.getLog(getClass());

	private Context context;

	public HibernateObsDAO() {
	}

	public HibernateObsDAO(Context c) {
		this.context = c;
	}

	/**
	 * @see org.openmrs.api.db.ObsService#createObs(org.openmrs.Obs)
	 */
	public void createObs(Obs obs) throws DAOException {

		Session session = HibernateUtil.currentSession();

		obs.setCreator(context.getAuthenticatedUser());

		// if dateCreated not set OR use lacks privilege to override, then set
		// dateCreated to current time
		if (obs.getDateCreated() == null
				|| !context
						.hasPrivilege(OpenmrsConstants.PRIV_OVERRIDE_DATE_CREATED))
			obs.setDateCreated(new Date());

		try {
			HibernateUtil.beginTransaction();
			session.save(obs);
			HibernateUtil.commitTransaction();
		}
		/*
		 * catch (HibernateException he) { try { HibernateUtil.closeSession();
		 * session = HibernateUtil.currentSession();
		 * HibernateUtil.beginTransaction(); session.clear(); session.save(obs);
		 * HibernateUtil.commitTransaction(); }
		 */

		catch (Exception e) {
			HibernateUtil.rollbackTransaction();
			throw new DAOException(e);
		}
		// }
	}

	/**
	 * @see org.openmrs.api.db.ObsService#deleteObs(org.openmrs.Obs)
	 */
	public void deleteObs(Obs obs) throws DAOException {

		Session session = HibernateUtil.currentSession();

		try {
			HibernateUtil.beginTransaction();
			session.delete(obs);
			HibernateUtil.commitTransaction();
		} catch (Exception e) {
			HibernateUtil.rollbackTransaction();
			throw new DAOException(e);
		}

	}

	/**
	 * @see org.openmrs.api.db.ObsService#getObs(java.lang.Integer)
	 */
	public Obs getObs(Integer obsId) throws DAOException {

		Session session = HibernateUtil.currentSession();

		Obs obs = new Obs();
		obs = (Obs) session.get(Obs.class, obsId);

		return obs;
	}

	public List<Obs> findObservations(Integer id, boolean includeVoided)
			throws DAOException {

		Session session = HibernateUtil.currentSession();

		List<Obs> obs = new Vector<Obs>();

		Criteria criteria = session.createCriteria(Obs.class).createAlias(
				"patient", "p").createAlias("encounter", "e").add(
				Expression.or(Expression.eq("p.patientId", id), Expression
						.like("e.encounterId", id)));

		if (includeVoided == false) {
			criteria.add(Expression.eq("voided", new Boolean(false)));
		}

		return criteria.list();
	}

	/**
	 * @see org.openmrs.api.db.ObsService#getMimeType(java.lang.Integer)
	 */
	public MimeType getMimeType(Integer mimeTypeId) throws DAOException {

		Session session = HibernateUtil.currentSession();

		MimeType mimeType = new MimeType();
		mimeType = (MimeType) session.get(MimeType.class, mimeTypeId);

		return mimeType;
	}

	/**
	 * @see org.openmrs.api.db.ObsService#getMimeTypes()
	 */
	public List<MimeType> getMimeTypes() throws DAOException {
		Session session = HibernateUtil.currentSession();

		List<MimeType> mimeTypes = session.createCriteria(MimeType.class)
				.list();

		return mimeTypes;
	}

	/**
	 * @see org.openmrs.api.db.ObsService#updateObs(org.openmrs.Obs)
	 */
	public void updateObs(Obs obs) throws DAOException {
		Session session = HibernateUtil.currentSession();

		if (obs.getObsId() == null)
			createObs(obs);
		else {
			try {
				HibernateUtil.beginTransaction();
				session.update(obs);
				HibernateUtil.commitTransaction();
			} catch (Exception e) {
				HibernateUtil.rollbackTransaction();
				throw new DAOException(e);
			}
		}
	}

	/**
	 * @see org.openmrs.api.db.ObsService#getLocation(java.lang.Integer)
	 */
	public Location getLocation(Integer locationId) throws DAOException {

		Session session = HibernateUtil.currentSession();

		Location location = new Location();
		location = (Location) session.get(Location.class, locationId);

		return location;

	}

	/**
	 * @see org.openmrs.api.db.ObsService#getLocations()
	 */
	public List<Location> getLocations() throws DAOException {

		Session session = HibernateUtil.currentSession();

		List<Location> locations;
		locations = session.createQuery("from Location l").list();

		return locations;

	}

	/**
	 * @see org.openmrs.api.db.ObsService#getObservations(org.openmrs.Encounter)
	 */
	public Set<Obs> getObservations(Encounter whichEncounter) {
		Session session = HibernateUtil.currentSession();

		Query query = session
				.createQuery("from Obs obs where obs.encounter = :e");
		query.setParameter("e", whichEncounter);
		Set<Obs> ret = new HashSet<Obs>(query.list());

		return ret;
	}

	/**
	 * @see org.openmrs.api.db.ObsService#getObservations(org.openmrs.Patient,
	 *      org.openmrs.Concept)
	 */
	public Set<Obs> getObservations(Patient who, Concept question) {
		Session session = HibernateUtil.currentSession();
		HibernateUtil.beginTransaction();

		Query query = session
				.createQuery("from Obs obs where obs.patient = :p and concept = :c");
		query.setParameter("p", who);
		query.setParameter("c", question);
		Set<Obs> ret = new HashSet<Obs>(query.list());

		HibernateUtil.commitTransaction();
		return ret;
	}

	/**
	 * @see org.openmrs.api.db.ObsService#getObservations(org.openmrs.Concept)
	 */
	public Set<Obs> getObservations(Concept question, String sort) {
		Session session = HibernateUtil.currentSession();
		HibernateUtil.beginTransaction();

		Query query = session
				.createQuery("from Obs obs where obs.concept = :c and obs.voided = false order by :sort");
		query.setParameter("c", question);
		query.setString("sort", sort);
		Set<Obs> ret = new HashSet<Obs>(query.list());

		HibernateUtil.commitTransaction();
		return ret;
	}

	/**
	 * @see org.openmrs.api.db.ObsService#getObservations(org.openmrs.Patient)
	 */
	public Set<Obs> getObservations(Patient who) {
		Session session = HibernateUtil.currentSession();
		HibernateUtil.beginTransaction();

		Query query = session
				.createQuery("from Obs obs where obs.patient = :p");
		query.setParameter("p", who);
		Set<Obs> ret = new HashSet<Obs>(query.list());

		HibernateUtil.commitTransaction();
		return ret;
	}
}
package org.openmrs.hl7;

import java.util.Date;
import java.util.Vector;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.Concept;
import org.openmrs.ConceptProposal;
import org.openmrs.Encounter;
import org.openmrs.EncounterType;
import org.openmrs.Form;
import org.openmrs.Location;
import org.openmrs.Obs;
import org.openmrs.Patient;
import org.openmrs.User;
import org.openmrs.api.context.Context;
import org.openmrs.formentry.FormEntryQueueProcessor;
import org.openmrs.util.OpenmrsConstants;

/**
 * Processes message in the HL7 inbound queue. Messages are moved into either
 * the archive or error table depending on success or failure of the processing.
 * 
 * @author Burke Mamlin
 * @version 1.0
 */
public class HL7InQueueProcessor implements Runnable {

	private Log log = LogFactory.getLog(this.getClass());

	private Context context;

	/**
	 * Empty constructor (requires context to be set before any other calls are
	 * made)
	 */
	public HL7InQueueProcessor() {
	}

	/**
	 * Default constructor
	 * 
	 * @param context
	 *            OpenMRS context
	 */
	public HL7InQueueProcessor(Context context) {
		this.context = context;
	}

	/**
	 * Process a single queue entry from the inbound HL7 queue
	 * 
	 * @param hl7InQueue
	 *            queue entry to be processed
	 */
	public void processHL7InQueue(HL7InQueue hl7InQueue) {

		HL7Message hl7Message;

		// Parse the HL7 into an HL7Message or abort with failure
		String hl7Data = hl7InQueue.getHL7Data();
		try {
			hl7Message = new HL7Message(hl7Data);
		} catch (HL7Exception e) {
			setFatalError(hl7InQueue, "Error parsing HL7 message", e);
			return;
		}

		// Check the HL7 version
		if (!hl7Message.getVersion().equals("2.5")) {
			setFatalError(hl7InQueue, "Unsupported version (2.5 only)", null);
			return;
		}

		// Branch based on the type of message
		String messageType = hl7Message.getMessageType();
		if (messageType.equals("ORU"))
			processORU(hl7InQueue, hl7Message);
		else {
			setFatalError(hl7InQueue, "Message type not supported: \""
					+ messageType + "\"", null);
			return;
		}
	}

	/**
	 * Transform the next pending HL7 inbound queue entry. If there are no
	 * pending items in the queue, this method simply returns quietly.
	 * 
	 * @return true if a queue entry was processed, false if queue was empty
	 */
	public boolean processNextHL7InQueue() {
		boolean entryProcessed = false;
		HL7Service hl7Service = context.getHL7Service();
		HL7InQueue hl7InQueue;
		if ((hl7InQueue = hl7Service.getNextHL7InQueue()) != null) {
			processHL7InQueue(hl7InQueue);
			entryProcessed = true;
		}
		return entryProcessed;
	}

	/**
	 * Process ORU messages (this will eventually be moved into a separate class
	 * as we expand to processing more message types ... or be replaced by part
	 * of an HL7 library)
	 * 
	 * @param hl7InQueue
	 *            inbound queue entry to be processed
	 * @param hl7Message
	 *            message generated from the queue entry
	 */
	public void processORU(HL7InQueue hl7InQueue, HL7Message hl7Message) {

		// Extract the event segment
		HL7Segment evn = hl7Message.getNextSegment("EVN");
		if (evn == null) {
			setFatalError(hl7InQueue, "Expected EVN segment", null);
			return;
		}

		// Extract the patient segment
		HL7Segment pid = hl7Message.getNextSegment("PID");
		if (pid == null) {
			setFatalError(hl7InQueue, "Expected PID segment", null);
			return;
		}

		// Extract the visit segment
		HL7Segment pv1 = hl7Message.getNextSegment("PV1");
		if (pv1 == null) {
			setFatalError(hl7InQueue, "Expected PV1 segment", null);
			return;
		}

		// Determine the enterer for obs data
		User enterer = null;
		try {
			enterer = getEnterer(evn);
		} catch (HL7Exception e) {
			setFatalError(hl7InQueue, "Unable to determine the data enterer", e);
			return;
		}

		// Determine the patient from the PID (patient) segment
		Patient patient = null;
		try {
			patient = getPatient(pid);
		} catch (HL7Exception e) {
			setFatalError(hl7InQueue, "Unable to determine the patient", e);
			return;
		}

		// Determine the provider from the PV1 (visit) segment
		User provider = null;
		try {
			provider = getProvider(pv1);
		} catch (HL7Exception e) {
			setFatalError(hl7InQueue, "Unable to determine the provider", e);
			return;
		}

		// Determine the form used (OpenMRS-specific -- we record the form
		// as a specific "profile" in MSH-21)
		Form form = null;
		try {
			form = getForm(hl7Message);
		} catch (Exception e) {
			setFatalError(hl7InQueue, "Unable to determine OpenMRS form", e);
			return;
		}

		// Create an encounter for the observations
		Encounter encounter = null;
		try {
			encounter = createEncounter(enterer, form, evn, pid, pv1);
		} catch (HL7Exception e) {
			setFatalError(hl7InQueue, "Unable to create encounter", e);
			return;
		}

		// Loop through the observations (grouped in OBRs, there should be
		// an OBR segment followed by 0..n OBX with an observation per OBX)
		Vector<Error> errors = new Vector<Error>();
		HL7Segment obr;
		while ((obr = hl7Message.getNextSegment("OBR")) != null) {
			HL7Segment obx;
			while (hl7Message.hasNextSegment("OBX")) {
				obx = hl7Message.getNextSegment();
				try {
					createObservation(enterer, patient, encounter, provider,
							obx);
				} catch (HL7Exception e) {
					addError(errors, obx.toString(), "OBX" + obx.getField(1)
							+ " parse error", e.getMessage());
				}
			}
		}
		if (errors != null && errors.size() > 0)
			recordErrors(hl7InQueue, errors);

		// Move HL7 inbound queue entry into the archive before exiting
		HL7InArchive hl7InArchive = new HL7InArchive(hl7InQueue);
		context.getHL7Service().createHL7InArchive(hl7InArchive);
		context.getHL7Service().deleteHL7InQueue(hl7InQueue);
	}

	/**
	 * Creates an encounter
	 */
	private Encounter createEncounter(User enterer, Form form, HL7Segment evn,
			HL7Segment pid, HL7Segment pv1) throws HL7Exception {
		Encounter encounter = new Encounter();

		Date encounterDate = HL7Util.parseHL7Date(pv1.getField(44));
		Date dateEntered = HL7Util.parseHL7Timestamp(evn.getField(2));
		EncounterType encounterType = form.getEncounterType();
		Location location = getLocation(pv1);
		Patient patient = getPatient(pid);
		User provider = getProvider(pv1);

		encounter.setEncounterDatetime(encounterDate);
		encounter.setEncounterType(encounterType);
		encounter.setForm(form);
		encounter.setLocation(location);
		encounter.setPatient(patient);
		encounter.setProvider(provider);
		encounter.setCreator(enterer);
		encounter.setDateCreated(dateEntered);
		context.getEncounterService().createEncounter(encounter);

		if (encounter == null || encounter.getEncounterId() == null
				|| encounter.getEncounterId() == 0) {
			throw new HL7Exception("Invalid encounter");
		}
		return encounter;
	}

	/**
	 * Extracts the data enterer from the EVN segment
	 */
	private User getEnterer(HL7Segment evn) throws HL7Exception {
		String hl7EntererId = evn.getComponent(5, 1);
		Integer entererId = null;
		User enterer;
		try {
			entererId = Integer.parseInt(hl7EntererId);
		} catch (NumberFormatException e) {
			throw new HL7Exception("Invalid enterer ID '" + hl7EntererId + "'");
		}
		if (entererId == null || entererId == 0)
			throw new HL7Exception("Unable to parse enterer ID '"
					+ hl7EntererId + "'");
		try {
			enterer = context.getUserService().getUser(entererId);
		} catch (Exception e) {
			throw new HL7Exception("Error retrieving enterer " + entererId, e);
		}
		if (enterer == null || enterer.getUserId() == null)
			throw new HL7Exception("Could not find enterer (User) " + entererId);
		return enterer;
	}

	/**
	 * Extract the patient from the PID segment
	 */
	private Patient getPatient(HL7Segment pid) throws HL7Exception {
		// TODO: assuming internal patient id is in first component (should be
		// verifying that ID is the proper type, doing lookup-by-name if
		// necessary, etc.)
		String hl7PatientId = pid.getComponent(3, 1);
		Integer patientId = null;
		Patient patient = null;
		try {
			patientId = Integer.parseInt(hl7PatientId);
		} catch (NumberFormatException e) {
			throw new HL7Exception("Invalid patient ID '" + hl7PatientId + "'");
		}
		if (patientId == null || patientId == 0)
			throw new HL7Exception("Unable to parse patient ID '"
					+ hl7PatientId + "'");

		try {
			patient = context.getPatientService().getPatient(patientId);
		} catch (Exception e) {
			throw new HL7Exception("Error retrieving patient " + patientId, e);
		}
		if (patient == null || patient.getPatientId() == null)
			throw new HL7Exception("Could not find patient " + patientId);
		return patient;
	}

	/**
	 * Extracts the form used to record the observations from the MSH segment
	 * (we store the formId as a MSH profile ID in MSH-21)
	 */
	private Form getForm(HL7Message hl7Message) throws HL7Exception {
		String hl7FormId = hl7Message.getProfileId().split(
				"\\" + hl7Message.getComponentSeparator())[0];
		Integer formId = null;
		Form form = null;
		try {
			formId = Integer.parseInt(hl7FormId);
		} catch (NumberFormatException e) {
			throw new HL7Exception("Invalid form ID '" + hl7FormId + "'");
		}
		if (formId == null || formId == 0) {
			throw new HL7Exception("Unable to parse OpenMRS form ID '"
					+ hl7FormId + "'");
		}
		try {
			form = context.getFormService().getForm(formId);
		} catch (Exception e) {
			throw new HL7Exception("Error retrieving OpenMRS form " + formId, e);
		}
		if (form == null || form.getFormId() == null)
			throw new HL7Exception("Could not find OpenMRS form " + formId);
		return form;
	}

	/**
	 * Extracts the location of the visit from the PV1 segment
	 */
	private Location getLocation(HL7Segment pv1) throws HL7Exception {
		// TODO: assuming internal location id is in first component (should be
		// verifying that ID is the proper type, doing lookup-by-name if
		// necessary, etc.)
		String hl7LocationId = pv1.getComponent(3, 1);
		Integer locationId = null;
		Location location;
		try {
			locationId = Integer.parseInt(pv1.getComponent(3, 1));
		} catch (NumberFormatException e) {
			throw new HL7Exception("Invalid location ID '" + hl7LocationId
					+ "'");
		}
		if (locationId == null || locationId == 0)
			throw new HL7Exception("Unable to parse location ID '"
					+ hl7LocationId + "'");
		try {
			location = context.getEncounterService().getLocation(locationId);
		} catch (Exception e) {
			throw new HL7Exception("Error retrieving location " + locationId, e);
		}
		if (location == null || location.getLocationId() == null)
			throw new HL7Exception("Could not find location " + locationId);
		return location;
	}

	/**
	 * Extracts the provider from the PV1 (visit) segment
	 */
	private User getProvider(HL7Segment pv1) throws HL7Exception {
		// TODO: just assuming internal provider ID is in first component
		// (should be veryfing that ID is the proper type, doing
		// lookup-by-name if necessary, etc.)
		String hl7ProviderId = pv1.getComponent(7, 1);
		Integer providerId = null;
		User provider;
		try {
			providerId = Integer.parseInt(hl7ProviderId);
		} catch (NumberFormatException e) {
			throw new HL7Exception("Invalid provider ID '" + hl7ProviderId
					+ "'");
		}
		if (providerId == null || providerId == 0)
			throw new HL7Exception("Unable to parse provider ID '"
					+ hl7ProviderId + "'");
		try {
			provider = context.getUserService().getUser(providerId);
		} catch (Exception e) {
			throw new HL7Exception("Error retrieving provider " + providerId, e);
		}
		if (provider == null || provider.getUserId() == null)
			throw new HL7Exception("Could not find provider (User) "
					+ providerId);
		return provider;
	}

	/**
	 * Creates an observation. If the observed conceptId matches the keyword for
	 * proposed concepts, then a ConceptProposal is generated instead.
	 */
	private void createObservation(User enterer, Patient patient,
			Encounter encounter, User provider, HL7Segment obx)
			throws HL7Exception {
		try {
			String hl7Datatype = obx.getField(2);
			Integer conceptId = Integer.parseInt(obx.getComponent(3, 1));
			Concept concept = context.getConceptService().getConcept(conceptId);
			// String subId = obx.getField(4);
			String value = obx.getField(5);
			String[] valueComponents = obx.getComponents(5);
			String dateTimeRaw = obx.getField(14);
			Date dateTime;
			if (dateTimeRaw.length() < 1)
				dateTime = encounter.getEncounterDatetime();
			else
				dateTime = HL7Util.parseHL7Timestamp(obx.getField(14));

			Obs obs = new Obs();
			obs.setPatient(patient);
			obs.setConcept(concept);
			obs.setEncounter(encounter);
			obs.setObsDatetime(dateTime);
			obs.setLocation(encounter.getLocation());
			obs.setCreator(enterer);
			if (hl7Datatype.equals("NM"))
				obs.setValueNumeric(Double.valueOf(value));
			else if (hl7Datatype.equals("CWE") || hl7Datatype.equals("CE")) {
				if (valueComponents[0]
						.equals(OpenmrsConstants.PROPOSED_CONCEPT_IDENTIFIER)) {
					proposeConcept(encounter, concept, valueComponents[1],
							enterer);
					return; // avoid trying to create an obs
				} else {
					Integer valueConceptId = Integer
							.parseInt(valueComponents[0]);
					Concept valueConcept = context.getConceptService()
							.getConcept(valueConceptId);
					obs.setValueCoded(valueConcept);
				}
			} else if (hl7Datatype.equals("DT")) {
				Date valueDate = HL7Util.parseHL7Date(value);
				obs.setValueDatetime(valueDate);
			} else if (hl7Datatype.equals("TS")) {
				Date valueTimestamp = HL7Util.parseHL7Timestamp(value);
				obs.setValueDatetime(valueTimestamp);
			} else if (hl7Datatype.equals("TM")) {
				Date valueTime = HL7Util.parseHL7Time(value);
				obs.setValueDatetime(valueTime);
			} else if (hl7Datatype.equals("ST"))
				obs.setValueText(value);
			else {
				// unsupported data type
				// TODO: support RP (report), SN (structured numeric)
				// do we need to support BIT just in case it slips thru?
			}
			context.getObsService().createObs(obs);

		} catch (Exception e) {
			throw new HL7Exception(e);
		}

	}

	/**
	 * Generates a ConceptProposal record
	 */
	private void proposeConcept(Encounter encounter, Concept concept,
			String originalText, User enterer) {
		// value is a proposed concept, create a ConceptProposal
		// instead of an Obs for this observation
		// TODO: at this point if componentSeparator (^) is in text,
		// we'll only use the text before that delimiter!
		ConceptProposal conceptProposal = new ConceptProposal();
		conceptProposal.setOriginalText(originalText);
		conceptProposal.setState(OpenmrsConstants.CONCEPT_PROPOSAL_UNMAPPED);
		conceptProposal.setEncounter(encounter);
		conceptProposal.setObsConcept(concept);
		context.getConceptService().proposeConcept(conceptProposal);
	}

	/**
	 * Convenience method to respond to fatal errors by moving the queue entry
	 * into an error bin prior to aborting
	 */
	private void setFatalError(HL7InQueue hl7InQueue, String error,
			Throwable cause) {
		HL7InError hl7InError = new HL7InError(hl7InQueue);
		hl7InError.setError(error);
		hl7InError.setErrorDetails(cause == null ? "" : cause.getMessage());
		context.getHL7Service().createHL7InError(hl7InError);
		context.getHL7Service().deleteHL7InQueue(hl7InQueue);
		log.error(error, cause);
	}

	/**
	 * Convenience method for tracking errors while processing observations
	 * (non-fatal errors)
	 */
	private void addError(Vector<Error> errors, String hl7Data, String error,
			String errorDetails) {
		errors.add(new Error(hl7Data, error, errorDetails));
	}

	/**
	 * Record (non-fatal) errors that occurred while processing observtions
	 */
	private void recordErrors(HL7InQueue hl7InQueue, Vector<Error> errors) {
		HL7InError hl7InError = new HL7InError();
		String hl7Data = "";
		String error = "";
		String errorDetails = "";
		for (Error err : errors) {
			hl7Data += err.hl7Data + "\r";
			error += err.error + "\n";
			errorDetails += err.errorDetails + "\n";
		}
		hl7InError.setHL7Source(hl7InQueue.getHL7Source());
		hl7InError.setHL7SourceKey(hl7InQueue.getHL7SourceKey());
		hl7InError.setHL7Data(hl7Data);
		hl7InError.setError(error);
		hl7InError.setErrorDetails(errorDetails);
		context.getHL7Service().createHL7InError(hl7InError);
	}

	/**
	 * Private inner class used to track errors while processing observations
	 */
	private class Error {
		String hl7Data;
		String error;
		String errorDetails;

		Error(String hl7Data, String error, String errorDetails) {
			this.hl7Data = hl7Data;
			this.error = error;
			this.errorDetails = errorDetails;
		}
	}

	/**
	 * Convenience method to allow for dependency injection
	 * 
	 * @param context
	 *            OpenMRS context to be used by the processor
	 */
	public void setContext(Context context) {
		this.context = context;
	}

	/**
	 * Run method for processing all entries in the HL7 inbound queue
	 */
	public void run() {
		try {
			while (processNextHL7InQueue()) {
				// loop until queue is empty
			}
		} catch (Exception e) {
			log.error("Error while processing HL7 inbound queue", e);
		}
	}

	/**
	 * Starts up a thread to process all existing FormEntryQueue entries
	 * 
	 * @param context
	 *            context from which process should start (required for
	 *            authentication)
	 */
	public static void processHL7InQueue(Context context) {
		HL7InQueueProcessor processor = new HL7InQueueProcessor(context);
		Thread t = new Thread(processor);
		t.start();
	}

}

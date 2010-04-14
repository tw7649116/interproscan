package uk.ac.ebi.interpro.scan.management.dao;

import org.springframework.transaction.annotation.Transactional;
import uk.ac.ebi.interpro.scan.genericjpadao.GenericDAOImpl;
import uk.ac.ebi.interpro.scan.management.model.*;

import javax.persistence.Query;
import java.util.*;

/**
 * DAO for StepInstance objects.  Used to retrieve
 * StepInstances that may be run.
 *
 * @author Phil Jones
 * @version $Id$
 * @since 1.0-SNAPSHOT
 */
public class StepInstanceDAOImpl extends GenericDAOImpl<StepInstance, String> implements StepInstanceDAO{

    private Map<SerialGroup, List<String>> serialGroupToStepIdMap = new HashMap<SerialGroup, List<String>>();
    /**
     * Sets the class of the model that the DOA instance handles.
     * Note that this has been set up to use constructor injection
     * because it makes it easy to sub-class GenericDAOImpl in a robust
     * manner.
     * <p/>
     * Model class specific sub-classes should define a no-argument constructor
     * that calls this constructor with the appropriate class.
     */
    public StepInstanceDAOImpl() {
        super(StepInstance.class);
    }

    /**
     * Retrieve the StepInstances from the database for a particular Step
     * that <b>MAY BE</b> candidates to be run.
     *
     * <b>NOTE: This returns all StepInstance objects that have not been
     * successfully run.  It does NOT filter out those that are
     * currently running - the calling code MUST call StepInstance.canBeSubmitted(Jobs jobs)
     * before creating a new StepExecution for the StepInstance.<b>
     *
     * @param step for which StepInstance objects should be obtained from the database.
     * @return a List of StepInstance objects that have not successfully completed yet.
     */
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public List<StepInstance> retrieveUnfinishedStepInstances(Step step) {
        Query query = entityManager.createQuery(
                        "select distinct i " +
                        "from StepInstance i " +
                        "where i.stepId = :stepId " +
                        "and i not in (" +
                                "select j " +
                                "from StepInstance j " +
                                "inner join j.executions e " +
                                "where e.state = :successful " +
                                "and j.stepId = :stepId) order by i.id desc");

        query.setParameter("stepId", step.getId());
        query.setParameter("successful", StepExecutionState.STEP_EXECUTION_SUCCESSFUL);
        return query.getResultList();
    }

    /**
     * Returns true if the SerialGroup passed in as argument
     * does not currently have a running instance. (submitted but not failed or completed)
     * instance.
     *
     * Should only be used by the Master - run in a synchronized Transaction,
     * together with JobExecution creation and JMS Job Submission.
     *
     * @return true if the SerialGroup passed in as argument
     *         does not currently have a running instance.
     */
    public boolean serialGroupCanRun(final StepInstance stepInstance, final Jobs jobs) {
        final SerialGroup serialGroup = stepInstance.getStep(jobs).getSerialGroup();
        if (serialGroup == null){
            return true;
        }
        if (! serialGroupToStepIdMap.containsKey(serialGroup)){
            serialGroupToStepIdMap.put (serialGroup, buildMapStepIdsInGroup (serialGroup, jobs));
        }
        final List<String> stepIds = serialGroupToStepIdMap.get(serialGroup);
        if (stepIds == null){    // There are no steps in this SerialGroup, so no restriction on running.  Should never be called though!
            return true;
        }
        // TODO - THis is using an in clause - need to ensure that the in clause never gets too big, so need to
        // iterate.
        final Query query = entityManager.createQuery(
                "select count(i) from StepInstance i inner join i.executions e " +
                "where i.stepId in (:stepIds) and e.submittedTime is not null and e.completedTime is null");
        query.setParameter("stepIds", stepIds);
        final long count = (Long)query.getSingleResult();
        return count == 0L;
    }

    /**
     * Returns true if there are steps left to run
     *
     * @return true if there are steps left to run
     */
    @Override
    public boolean futureStepsAvailable() {
        Query query = entityManager.createQuery(
                        "select count(i) " +
                        "from StepInstance i " +
                        "where i not in (" +
                                "select j " +
                                "from StepInstance j " +
                                "inner join j.executions e " +
                                "where e.state = :successful)");

        query.setParameter("successful", StepExecutionState.STEP_EXECUTION_SUCCESSFUL);
        return ((Long)query.getSingleResult()) > 0L;
    }

    private List<String> buildMapStepIdsInGroup(SerialGroup serialGroup, Jobs jobs) {
        List<String> stepIds = new ArrayList<String>();
        for (Job job : jobs.getJobList()){
            for (Step step : job.getSteps()){
                if (serialGroup == step.getSerialGroup()){
                    stepIds.add (step.getId());
                }
            }
        }
        return stepIds;
    }
}

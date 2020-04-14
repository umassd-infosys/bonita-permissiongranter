package edu.umassd.bonita;
/*
 *
 * Event Handler that fires on human task assignment.
 * 
 * Looks at the task, determines all possible candidates to complete it and adds a "dummy" archived task
 * for all possible user candidates that do not already have an archived task on the case.
 * 
 * This is to grant the user permissions to this case.
 * 
 * Processes can be excluded from evaluation by adding either a process category (on the fly) or a process parameter
 * named to match this class' skipParameter property
 * 
 * 
 * 1.0.0 initial release: mpr April 2020
 * 
 */
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import org.bonitasoft.engine.core.category.CategoryService;
import org.bonitasoft.engine.core.category.exception.SCategoryException;
import org.bonitasoft.engine.core.category.exception.SIndexOutOfRangeException;
import org.bonitasoft.engine.core.category.model.SCategory;
import org.bonitasoft.engine.core.connector.ConnectorInstanceService;
import org.bonitasoft.engine.core.contract.data.ContractDataService;
import org.bonitasoft.engine.core.process.definition.ProcessDefinitionService;
import org.bonitasoft.engine.core.process.definition.model.SFlowNodeType;
import org.bonitasoft.engine.core.process.instance.api.ActivityInstanceService;
import org.bonitasoft.engine.core.process.instance.api.ProcessInstanceService;
import org.bonitasoft.engine.core.process.instance.api.exceptions.SActivityCreationException;
import org.bonitasoft.engine.core.process.instance.api.exceptions.SActivityReadException;
import org.bonitasoft.engine.events.model.SEvent;
import org.bonitasoft.engine.events.model.SHandler;
import org.bonitasoft.engine.events.model.SHandlerExecutionException;
import org.bonitasoft.engine.execution.archive.ProcessArchiver;
import org.bonitasoft.engine.SArchivingException;
import org.bonitasoft.engine.archive.ArchiveService;
import org.bonitasoft.engine.core.process.instance.model.SHumanTaskInstance;
import org.bonitasoft.engine.core.process.instance.model.STaskPriority;
import org.bonitasoft.engine.core.process.instance.model.SUserTaskInstance;
import org.bonitasoft.engine.core.process.instance.model.archive.SAActivityInstance;
import org.bonitasoft.engine.data.instance.api.DataInstanceService;
import org.bonitasoft.engine.parameter.OrderBy;
import org.bonitasoft.engine.parameter.ParameterService;
import org.bonitasoft.engine.parameter.SOutOfBoundException;
import org.bonitasoft.engine.parameter.SParameter;
import org.bonitasoft.engine.persistence.OrderByType;
import org.bonitasoft.engine.persistence.QueryOptions;
import org.bonitasoft.engine.persistence.SBonitaReadException;
import org.bonitasoft.engine.service.TenantServiceAccessor;
import org.bonitasoft.engine.service.impl.ServiceAccessorFactory;

/**
 * 
 * BonitaProperties extends Properties except for Stream argument.
 * Using the stream argument (to save document for example), use only the method get / set
 *
 */
public class SecurityGranter implements SHandler<SEvent>  {


    /* The name of a boolean process parameter OR process category 
     * that would indicate we skip this process from being evaluated */
    private final String skipParameter = "skipAutoPermissionGranting";
    /* Maximum number of user search results -- we need to have some sort of limit in 
     * case a task is mapped to just "member" or some otherwise overly large group */
    private final int maxResults = 25;
    private long tenantId = 1L; //default tenant is 1, but use what comes across to us
    private final String newTaskName = "Grant permissions to user";
    private final String newTaskDescription = "A utility task that grants permissions to task candidates.";
    private final long delegateId = 1L; //The task will be executed as this user on behalf of the user we're granting permissions to

    private static Logger logger = Logger.getLogger(SecurityGranter.class.getName());
	private static final long serialVersionUID = 5892801961711156471L;
    private TenantServiceAccessor tenantAccessor; 
    private final String identifier = UUID.randomUUID().toString();
    
    /* Constructor */
    public SecurityGranter(final long tenantId) throws SHandlerExecutionException {
    	this.tenantId=tenantId;
    }

    /* workhorse to create our dummy task */
    public SHumanTaskInstance createDummyTask(SHumanTaskInstance currentTask,long userId) {
		long caseId = currentTask.getParentProcessInstanceId();
		long logGroup1 = currentTask.getLogicalGroup1();
		long logGroup2 = currentTask.getLogicalGroup2();
       	SHumanTaskInstance newTask = new SUserTaskInstance(this.newTaskName, currentTask.getFlowNodeDefinitionId(), caseId, caseId, currentTask.getActorId(),STaskPriority.LOWEST, logGroup1, logGroup2);
		newTask.setDescription(this.newTaskDescription);
       	newTask.setExecutedBySubstitute(this.delegateId);
		newTask.setExecutedBy(userId);
		newTask.setAssigneeId(userId);
		newTask.setStateName("completed");
		newTask.setStateId(2);;
		newTask.setStable(true);
		newTask.setTerminal(true);
		newTask.setClaimedDate(currentTask.getReachedStateDate());
		newTask.setTenantId(currentTask.getTenantId());
		return newTask;
    }
    
    @Override
    public void execute(SEvent event) throws SHandlerExecutionException {
    	SHumanTaskInstance myTask = (SHumanTaskInstance) event.getObject( );
		SHumanTaskInstance newTask ;
		Long taskId = myTask.getId(); 
		Long caseId = myTask.getParentProcessInstanceId();
		Long processDefinitionId = myTask.getProcessDefinitionId();
		QueryOptions queryOptions = new QueryOptions(0,500); //max # of Tasks to return for evaluation
		List<SAActivityInstance> aai = null;
		List<Long> possibleUsers = null;
		ParameterService parameterService = getTenantAccessor().getParameterService();
		CategoryService categoryService = getTenantAccessor().getCategoryService();
	
		
		/* Check to see if this process has definitions that means we should ignore it */
		//Check 1: Is there a process parameter defined?
		try {
			List<SParameter> parameters = parameterService.get(processDefinitionId, 0, 500, OrderBy.NAME_DESC);
			for(SParameter parameter : parameters) {
				if(parameter.getName().equalsIgnoreCase(skipParameter)) {
					if(Boolean.parseBoolean(parameter.getValue())==true) {
						//this process has the magic parameter and it is set to true
						return;
					} else {
						//don't bother going forward -- it exists but it isn't true
						continue;
					}
				}
			}
		} catch (SBonitaReadException e2) {
			// not a big deal, just continue
		} catch (SOutOfBoundException e) {
			//not a big deal, just continue
		}
		//Check 2: Is there a process category defined?
		try {
			List<SCategory> categories = categoryService.getCategoriesOfProcessDefinition(processDefinitionId, 0, 500, OrderByType.ASC_NULLS_LAST);
			for(SCategory category : categories) {
				if(category.getName().equalsIgnoreCase(skipParameter)) {
					//This process has the magic category assigned to it
					return;
				}
			}
		} catch (SIndexOutOfRangeException e2) {
			// meh, doesn't matter
		} catch (SCategoryException e2) {
			// meh, doesn't matter
		}
		ActivityInstanceService activityInstanceService = getTenantAccessor().getActivityInstanceService();

		//Look up all the current archived tasks that exist, because we don't want to add a user twice if we can help it
		try {
			
			aai = activityInstanceService.getArchivedActivityInstances(caseId, queryOptions);
		} catch (SActivityReadException ex) {
			logger.info("Could not get archived instances for case "+caseId);
			ex.printStackTrace();
		}
		//Get all the possible users for the task that caused this process to fire
		try {
			possibleUsers = activityInstanceService.getPossibleUserIdsOfPendingTasks(taskId, 0, maxResults);
			if(possibleUsers.size()==1) {
				/*If there's only one user then there's only going to be one possible executor, 
				 * so this is redundant and we can just exit
				 */
				return; 
			}
		} catch (SActivityReadException e1) {
	        logger.info("Could not execute task search for possible executers of task "+taskId.toString());
	        logger.info(e1.getMessage());
			e1.printStackTrace();
		}
		
		/* Initialize all the service references various utility methods need to operate */
		ProcessInstanceService piService = getTenantAccessor().getProcessInstanceService();
		ArchiveService archiveService = getTenantAccessor().getArchiveService();
		ProcessDefinitionService pdService = getTenantAccessor().getProcessDefinitionService();
		DataInstanceService diService = getTenantAccessor().getDataInstanceService();
		ConnectorInstanceService ciService = getTenantAccessor().getConnectorInstanceService();
		ContractDataService cdService = getTenantAccessor().getContractDataService();			
		ProcessArchiver pa = new ProcessArchiver();
		
		/* name this loop so we can continue out of it if a user already has access */
		userChecker: for(long userId: possibleUsers) {
			//Is this user already in scope for this process?
			if(myTask.getAssigneeId()==userId) {
				continue userChecker;
			}
			for(SAActivityInstance oldInstance: aai) {
				if(oldInstance.getExecutedBy()==userId || oldInstance.getExecutedBySubstitute()==userId) {
					//This user already exists, continue on in the parent loop
					logger.info("Access already exists for user "+userId +" on case "+caseId);
					continue userChecker;
				}
			}
			logger.fine("Creating new task for user "+userId+ " on case "+caseId);
			newTask = createDummyTask(myTask,userId);
        	try {
				activityInstanceService.createActivityInstance(newTask);
				//Archive this activity instantly
				
				pa.archiveFlowNodeInstance(newTask, true, myTask.getProcessDefinitionId(), piService, pdService, archiveService, diService, activityInstanceService, ciService, cdService);
			} catch (SActivityCreationException e) {
		        logger.info("Could not add new dummy task "+taskId.toString());
		        logger.info(e.getMessage());
		        e.printStackTrace();
			} catch (SArchivingException e) {
				// TODO Auto-generated catch block
				logger.info("Could not ARCHIVE new dummy task");
				e.printStackTrace();
			}
		}
    }

    @Override
    public boolean isInterested(SEvent event) {
        Object eventObject = event.getObject();
        //We only want to fire on SHumanTaskInstance objects
        if (eventObject instanceof SHumanTaskInstance) {
        	SHumanTaskInstance sHumanTaskInstance = (SHumanTaskInstance) eventObject;
			return (sHumanTaskInstance.getType().equals(SFlowNodeType.USER_TASK) && sHumanTaskInstance.getStateId()==4);
        }
        return false;
    }
	public long getTenantId() {
		return tenantId;
	}
	
	private TenantServiceAccessor getTenantServiceAccessor() throws SHandlerExecutionException {
		try {
			ServiceAccessorFactory serviceAccessorFactory = ServiceAccessorFactory.getInstance();
			return serviceAccessorFactory.createTenantServiceAccessor(getTenantId());
		} catch (Exception e) {
			throw new SHandlerExecutionException(e.getMessage(), null);
		}
	} 
	
	public TenantServiceAccessor getTenantAccessor() throws SHandlerExecutionException {
		if (tenantAccessor!=null) {
			return tenantAccessor;
		}
		tenantAccessor = getTenantServiceAccessor();
		return tenantAccessor;
	}
    
    @Override
    public String getIdentifier() {
        return identifier;
    }
}

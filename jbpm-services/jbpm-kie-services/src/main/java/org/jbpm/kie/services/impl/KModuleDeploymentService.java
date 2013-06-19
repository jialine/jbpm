package org.jbpm.kie.services.impl;

import static org.kie.scanner.MavenRepository.getMavenRepository;

import java.io.UnsupportedEncodingException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.spi.BeanManager;
import javax.inject.Inject;
import javax.persistence.EntityManagerFactory;

import org.apache.commons.codec.binary.Base64;
import org.drools.compiler.kie.builder.impl.InternalKieModule;
import org.drools.compiler.kie.builder.impl.KieContainerImpl;
import org.drools.core.util.StringUtils;
import org.jbpm.kie.services.api.DeploymentUnit;
import org.jbpm.kie.services.api.IdentityProvider;
import org.jbpm.kie.services.api.Kjar;
import org.jbpm.kie.services.api.bpmn2.BPMN2DataService;
import org.jbpm.kie.services.impl.audit.ServicesAwareAuditEventBuilder;
import org.jbpm.kie.services.impl.model.ProcessDesc;
import org.jbpm.process.audit.AbstractAuditLogger;
import org.jbpm.process.audit.AuditLoggerFactory;
import org.jbpm.runtime.manager.impl.RuntimeEnvironmentBuilder;
import org.jbpm.runtime.manager.impl.cdi.InjectableRegisterableItemsFactory;
import org.kie.api.KieBase;
import org.kie.api.KieServices;
import org.kie.api.builder.ReleaseId;
import org.kie.api.runtime.KieContainer;
import org.kie.scanner.MavenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonatype.aether.artifact.Artifact;

@ApplicationScoped
@Kjar
public class KModuleDeploymentService extends AbstractDeploymentService {

    private static Logger logger = LoggerFactory.getLogger(KModuleDeploymentService.class);

    @Inject
    private BeanManager beanManager;
    @Inject
    private EntityManagerFactory emf;
    @Inject
    private IdentityProvider identityProvider; 
    @Inject
    private BPMN2DataService bpmn2Service;
    
    @Override
    public void deploy(DeploymentUnit unit) {
        super.deploy(unit);
        if (!(unit instanceof KModuleDeploymentUnit)) {
            throw new IllegalArgumentException("Invalid deployment unit provided - " + unit.getClass().getName());
        }
        KModuleDeploymentUnit kmoduleUnit = (KModuleDeploymentUnit) unit;
        DeployedUnitImpl deployedUnit = new DeployedUnitImpl(unit);
        KieServices ks = KieServices.Factory.get();
        MavenRepository repository = getMavenRepository();
        Artifact artifact = repository.resolveArtifact(kmoduleUnit.getIdentifier());
        
        ReleaseId releaseId = ks.newReleaseId(kmoduleUnit.getGroupId(), kmoduleUnit.getArtifactId(), kmoduleUnit.getVersion());
        KieContainer kieContainer = ks.newKieContainer(releaseId);
        
        String kbaseName = kmoduleUnit.getKbaseName();
        if (StringUtils.isEmpty(kbaseName)) {
            kbaseName = ((KieContainerImpl)kieContainer).getKieProject().getDefaultKieBaseModel().getName();
        } 
        InternalKieModule module = (InternalKieModule) ((KieContainerImpl)kieContainer).getKieModuleForKBase(kbaseName);
        
        Map<String, String> formsData = new HashMap<String, String>();
        Collection<String> files = module.getFileNames();
        for (String fileName : files) {
            if(fileName.matches(".+bpmn[2]?$")) {
                ProcessDesc process;
                try {
                    String processString = new String(module.getBytes(fileName), "UTF-8");
                    process = bpmn2Service.findProcessId(processString, kieContainer.getClassLoader());
                    process.setEncodedProcessSource(Base64.encodeBase64String(processString.getBytes()));
                    process.setDeploymentId(unit.getIdentifier());
                    process.setForms(formsData);
                    deployedUnit.addAssetLocation(process.getId(), process);
                    
                } catch (UnsupportedEncodingException e) {
                    logger.warn("Unable to load content for file '" + fileName + "': ", e);
                }
            } else if (fileName.matches(".+ftl$")) {
                try {
                    String formContent = new String(module.getBytes(fileName), "UTF-8");
                    Pattern regex = Pattern.compile("(.{0}|.*/)([^/]*?)\\.ftl");
                    Matcher m = regex.matcher(fileName);
                    String key = fileName;
                    while (m.find()) {
                        key = m.group(2);
                    }
                    formsData.put(key, formContent);
                } catch (UnsupportedEncodingException e) {
                    logger.warn("Unable to load content for form '" + fileName + "': ", e);
                }
            } else if (fileName.matches(".+form$")) {
                try {
                    String formContent = new String(module.getBytes(fileName), "UTF-8");
                    formsData.put(fileName, formContent);
                } catch (UnsupportedEncodingException e) {
                    logger.warn("Unable to load content for form '" + fileName + "': ", e);
                }
            }
        }
        
        KieBase kbase = null;
        if (StringUtils.isEmpty(kmoduleUnit.getKbaseName())) {
            kbase = kieContainer.getKieBase();            
        } else {
            kbase = kieContainer.getKieBase(kmoduleUnit.getKbaseName());
        }
        
        AbstractAuditLogger auditLogger = AuditLoggerFactory.newJPAInstance(emf);
        ServicesAwareAuditEventBuilder auditEventBuilder = new ServicesAwareAuditEventBuilder();
        auditEventBuilder.setIdentityProvider(identityProvider);
        auditEventBuilder.setDeploymentUnitId(unit.getIdentifier());
        auditLogger.setBuilder(auditEventBuilder);
        
        RuntimeEnvironmentBuilder builder = RuntimeEnvironmentBuilder.getDefault()
                .entityManagerFactory(emf)
                .knowledgeBase(kbase);
        if (beanManager != null) {
            builder.registerableItemsFactory(InjectableRegisterableItemsFactory.getFactory(beanManager, auditLogger, kieContainer, 
                    kmoduleUnit.getKsessionName()));
        }
        
        commonDeploy(unit, deployedUnit, builder.get());
    }

}

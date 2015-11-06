package org.infinispan.util;

import org.infinispan.util.logging.Log;
import org.infinispan.util.logging.LogFactory;
import org.junit.runner.Description;
import org.junit.runner.notification.RunListener;


/**
 * Logs test methods.
 * 
 * @author <a href="mailto:mlinhard@redhat.com">Michal Linhard</a>
 *
 */
public class TestsuiteListener extends RunListener {

    private static final Log log = LogFactory.getLog(TestsuiteListener.class);    

    @Override
    public void testStarted(Description description) throws Exception {
        super.testStarted(description);
        log.info("Running " + description.getClassName() + "#" + description.getMethodName());
    }
}

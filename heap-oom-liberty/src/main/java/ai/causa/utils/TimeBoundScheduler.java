package ai.causa.utils;

import ai.causa.svc.AllocatorService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.concurrent.ManagedScheduledExecutorService;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

@ApplicationScoped
public class TimeBoundScheduler {
    private static final Logger LOG = Logger.getLogger(TimeBoundScheduler.class.getName());

    @ConfigProperty(name = "crash.time.tick-millis", defaultValue = "500")
    long tickMillis;

    @Resource
    ManagedScheduledExecutorService scheduledExecutor;

    @Inject
    AllocatorService svc;

    @PostConstruct
    void init() {
        validateTickMillis();
        scheduleTask();
    }

    void validateTickMillis() {
        if (tickMillis != 100 && tickMillis != 500 && tickMillis != 1000) {
            LOG.warning(String.format(
                    "Unsupported crash.time.tick-millis value %d; auto-allocation on deadline will be disabled. " +
                            "Supported values are 100, 500, or 1000 milliseconds.",
                    tickMillis));
        }
    }

    void scheduleTask() {
        // Schedule the task to run at the configured interval
        scheduledExecutor.scheduleAtFixedRate(
                () -> svc.maybeAutoAllocateOnDeadline(),
                tickMillis,
                tickMillis,
                TimeUnit.MILLISECONDS);
    }
}

package com.github.icikle.nestedjarfile_reproduce;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.SmartApplicationListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.VirtualThreadTaskExecutor;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

@SpringBootApplication
public class NestedjarfileReproduceApplication {

  public static void main(String[] args) {
    SpringApplication app = new SpringApplication(NestedjarfileReproduceApplication.class);
    app.addInitializers(new NestedJarFileContentionInitializer());
    app.run(args);
  }


  @Bean
  public AsyncTaskExecutor getTaskExecutor() {
    return new VirtualThreadTaskExecutor("virtual-thread-executor");
  }

  @Bean
  public HealthIndicator myHealthIndicator() {
    return new MyHealthIndicator();
  }

  @Bean
  public SmartApplicationListener appRunner() {
    return new AppRunner();
  }

  public static class MyHealthIndicator implements HealthIndicator {
    @Override
    public @Nullable Health health() {
      try {
        Thread.sleep(500);
      } catch (InterruptedException e) {
      }
      return Health.up().build();
    }

    @Override
    public @Nullable Health health(boolean includeDetails) {
      return HealthIndicator.super.health(includeDetails);
    }
  }

  @Bean
  public Map<String, List<String>> configSources() {
    return Map.of(
        "app1", List.of("com/github/icikle/packone", "com/github/icikle/packtwo", "com/github/icikle/packapp1"),
        "app2", List.of("com/github/icikle/packone", "com/github/icikle/packtwo", "com/github/icikle/packapp2"),
        "app3", List.of("com/github/icikle/packone", "com/github/icikle/packtwo", "com/github/icikle/packapp3"),
        "app4", List.of("com/github/icikle/packone", "com/github/icikle/packtwo", "com/github/icikle/packapp4")
    );
  }


  public static class AppRunner implements SmartApplicationListener, ApplicationContextAware {

    @Autowired
    AsyncTaskExecutor taskExecutor;

    @Autowired
    Map<String, List<String>> configSources;

    private ApplicationContext applicationContext;

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
      this.applicationContext = applicationContext;
    }

    public void processFile(Resource resource){
      try (java.io.InputStream in = resource.getInputStream()) {
        // Use content as digest
        final byte[] buffer = new byte[8192 * 4];

        int read = in.read(buffer, 0, buffer.length);
        while (read > -1) {
          read = in.read(buffer, 0, buffer.length);
        }

      } catch (Exception e) {
        throw new RuntimeException("Failed to get fingerprint for " , e);
      }
    }
    public void load(String k, String path) {
      try {
        Resource[] resources = applicationContext.getResources("classpath:" + path + "/**");
        Arrays.stream(resources)
            .filter(resource -> {
              try {
                java.net.URI p = resource.getURI();
                return p.toString().endsWith(".xml") || p.toString().endsWith(".json");
              } catch (IOException e) {
                e.printStackTrace();
                throw new RuntimeException(e);
              }
            })
            .forEach(resource -> {

                System.out.println("Loading 'configuration' " + k + " :" + resource.getFilename());
                processFile(resource);
//                try (java.io.InputStream in = resource.getInputStream()) {
//                  // Use content as digest
//                  final byte[] buffer = new byte[8192 * 4];
//
//                  int read = in.read(buffer, 0, buffer.length);
//                  while (read > -1) {
//                    read = in.read(buffer, 0, buffer.length);
//                  }
//
//                } catch (Exception e) {
//                  throw new RuntimeException("Failed to get fingerprint for " + resource.getURI(), e);
//                }
//                System.out.println("Read " +bytes.length +" from " +resource.getFilename());

            });
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }

    @Override
    public void onApplicationEvent(ApplicationEvent event) {
      CountDownLatch countDownLatch = new CountDownLatch(configSources.size());

      configSources.forEach((k, v) -> {
        taskExecutor.execute(() -> {
          System.out.println("Loading 'configuration' " + k);
          v.stream()
              .filter(path -> new ClassPathResource(path).exists())
              .forEach(path -> {
                load(k, path);
                System.out.println("Done 'configuration' " + k);
              });
          countDownLatch.countDown();

        });
      });
      try {
        countDownLatch.await();
        System.out.println("Done ALL");
      } catch (InterruptedException e) {
        e.printStackTrace();
      }
    }

    @Override
    public boolean supportsEventType(Class<? extends ApplicationEvent> eventType) {
      return ApplicationStartedEvent.class.isAssignableFrom(eventType);
    }
  }
}

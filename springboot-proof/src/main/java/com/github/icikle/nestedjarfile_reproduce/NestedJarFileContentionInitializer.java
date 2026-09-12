package com.github.icikle.nestedjarfile_reproduce;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs before any bean definitions are loaded (main thread, inside
 * SpringApplication.prepareContext -> applyInitializers), so it can put the
 * main thread itself into the NestedJarFile contention while background
 * virtual threads are also hammering the same nested jars - mirroring
 * Verne's main thread blocking inside ApplicationContextManagerImpl.startup()
 * while the Mongo driver monitor thread concurrently classloads from the
 * same nested jar.
 */
public class NestedJarFileContentionInitializer
    implements ApplicationContextInitializer<ConfigurableApplicationContext> {

  private static final Map<String, List<String>> CONFIG_SOURCES = Map.of(
      "app1", List.of("com/github/icikle/packone", "com/github/icikle/packtwo", "com/github/icikle/packapp1"),
      "app2", List.of("com/github/icikle/packone", "com/github/icikle/packtwo", "com/github/icikle/packapp2"),
      "app3", List.of("com/github/icikle/packone", "com/github/icikle/packtwo", "com/github/icikle/packapp3"),
      "app4", List.of("com/github/icikle/packone", "com/github/icikle/packtwo", "com/github/icikle/packapp4")
  );

  @Override
  public void initialize(ConfigurableApplicationContext context) {
    AtomicBoolean stop = new AtomicBoolean(false);
    ExecutorService background = Executors.newVirtualThreadPerTaskExecutor();

    CONFIG_SOURCES.forEach((app, paths) -> background.execute(() -> {
      while (!stop.get()) {
        paths.forEach(path -> scan(context, app, path));
      }
    }));

    // Mirrors ElasticConfig$ElasticHealth.health() in the real dump: it never
    // executes an HTTP call, it just builds a RestClient request and calls
    // .uri(template, vars) to expand it - DefaultRestClient.uri() ->
    // DefaultUriBuilderFactory.expand() -> UriComponents.expand() ->
    // ClassLoader.loadClass(), all before anything is sent over the wire.
    // Several concurrent virtual threads doing exactly that races on
    // ClassLoader.loadClass's per-class-name lock for whatever class URI
    // template expansion needs on first use - no Tomcat/actuator/real HTTP
    // traffic required to get that link in the chain.
    for (int i = 0; i < URI_EXPANSION_THREADS; i++) {
      final int id = i;
      background.execute(() -> {
        RestClient client = RestClient.builder().build();
        while (!stop.get()) {
          client.get().uri("http://nestedjarfile-reproduce.invalid/health/{id}", id);
        }
      });
    }

    context.addApplicationListener((ApplicationListener<ApplicationReadyEvent>) event -> {
      stop.set(true);
      background.shutdown();
    });

    // Slow, lock-holding read on the main thread itself, before refresh()
    // has loaded a single bean definition. packtwo is scanned by every
    // background loop above, so main contends for the same NestedJarFile
    // lock as all four of them, not just one.
    scan(context, "main-thread", "com/github/icikle/packtwo");
  }

  private static final int URI_EXPANSION_THREADS = 8;

  // Artificial per-resource throttle to stretch the wall-clock window up to
  // something a human can jcmd into. Deliberately a CPU busy-spin, not
  // Thread.sleep(): sleep is a parking point, so a virtual thread would
  // unmount and hand its carrier back to the scheduler, which *relieves*
  // contention on the 3-carrier pool instead of adding to it. A busy-spin
  // has no parking point, so the virtual thread stays mounted and keeps
  // occupying its carrier for the whole throttle - the same "pins the
  // carrier" effect FileChannel reads have in the real bug, just applied to
  // our own throttle instead of to an actual disk read.
  private static final long THROTTLE_MILLIS = 15;

  private void scan(ConfigurableApplicationContext context, String label, String path) {
    if (!new ClassPathResource(path).exists()) {
      return;
    }
    try {
      Resource[] resources = context.getResources("classpath:" + path + "/**");
      Arrays.stream(resources)
          .filter(NestedJarFileContentionInitializer::isConfigFile)
          .forEach(resource -> {
            drain(label, resource);
            busySpin(THROTTLE_MILLIS);
          });
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private static void busySpin(long millis) {
    long end = System.nanoTime() + millis * 1_000_000L;
    while (System.nanoTime() < end) {
      // deliberately not a parking call - keeps the (possibly virtual)
      // thread mounted on its carrier for the full duration
    }
  }

  private static boolean isConfigFile(Resource resource) {
    try {
      String uri = resource.getURI().toString();
      return uri.endsWith(".xml") || uri.endsWith(".json");
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private static void drain(String label, Resource resource) {
    try (InputStream in = resource.getInputStream()) {
      byte[] buffer = new byte[8192 * 4];
      while (in.read(buffer, 0, buffer.length) > -1) {
        // draining forces the same FileDataBlock/NestedJarFile decompressing
        // read path as Verne's AbstractVfs.getFingerPrint
      }
    } catch (IOException e) {
      throw new RuntimeException("[" + label + "] failed to read " + resource, e);
    }
  }
}

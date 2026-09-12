# CPU count is clamped to match the verne StatefulSet's container CPU limit
# (chart/verne/values.yaml resources.limits.cpu: 3000m -> 3 cores; the test
# override in chart/verne/tests/verne-statefulset_test.yaml goes as low as 1000m).
# Fewer available processors means fewer virtual-thread carrier threads
# (jdk.virtualThreadScheduler.parallelism defaults to availableProcessors()),
# which is what makes NestedJarFile lock contention actually pile up instead
# of resolving in sub-millisecond time on a many-core dev laptop.
CPU_COUNT="${CPU_COUNT:-3}"

java \
  -XX:ActiveProcessorCount="${CPU_COUNT}" \
  -Djdk.tracePinnedThreads=full \
  -Dloader.path=loader-lib -Dloader.main=com.github.icikle.nestedjarfile_reproduce.NestedjarfileReproduceApplication -jar build/libs/nestedjarfile-reproduce-0.0.1-SNAPSHOT.jar org.springframework.boot.loader.launch.PropertiesLauncher

/*
 * Copyright 2014-2017 Spotify AB
 * Copyright 2016-2019 The Last Pickle Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.cassandrareaper.storage;

import io.cassandrareaper.core.Cluster;
import io.cassandrareaper.core.DiagEventSubscription;
import io.cassandrareaper.core.PercentRepairedMetric;
import io.cassandrareaper.core.RepairRun;
import io.cassandrareaper.core.RepairSchedule;
import io.cassandrareaper.core.RepairSegment;
import io.cassandrareaper.core.RepairUnit;
import io.cassandrareaper.storage.cluster.IClusterDao;
import io.cassandrareaper.storage.cluster.MemoryClusterDao;
import io.cassandrareaper.storage.events.IEventsDao;
import io.cassandrareaper.storage.events.MemoryEventsDao;
import io.cassandrareaper.storage.memory.MemoryStorageRoot;
import io.cassandrareaper.storage.metrics.MemoryMetricsDao;
import io.cassandrareaper.storage.repairrun.IRepairRunDao;
import io.cassandrareaper.storage.repairrun.MemoryRepairRunDao;
import io.cassandrareaper.storage.repairschedule.IRepairScheduleDao;
import io.cassandrareaper.storage.repairschedule.MemoryRepairScheduleDao;
import io.cassandrareaper.storage.repairsegment.IRepairSegmentDao;
import io.cassandrareaper.storage.repairsegment.MemoryRepairSegmentDao;
import io.cassandrareaper.storage.repairunit.IRepairUnitDao;
import io.cassandrareaper.storage.repairunit.MemoryRepairUnitDao;
import io.cassandrareaper.storage.snapshot.ISnapshotDao;
import io.cassandrareaper.storage.snapshot.MemorySnapshotDao;

import java.nio.file.Paths;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.eclipse.store.storage.embedded.types.EmbeddedStorage;
import org.eclipse.store.storage.embedded.types.EmbeddedStorageManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Implements the StorageAPI using transient Java classes.
 */
public final class MemoryStorageFacade implements IStorageDao {

  private static final Logger LOG = LoggerFactory.getLogger(MemoryStorageFacade.class);
  private final EmbeddedStorageManager embeddedStorage;
  private final MemoryStorageRoot memoryStorageRoot;
  private final MemoryRepairSegmentDao memRepairSegment = new MemoryRepairSegmentDao(this);
  private final MemoryRepairUnitDao memoryRepairUnitDao = new MemoryRepairUnitDao(this);
  private final MemoryRepairRunDao memoryRepairRunDao =
      new MemoryRepairRunDao(this, memRepairSegment, memoryRepairUnitDao);
  private final MemoryRepairScheduleDao memRepairScheduleDao = new MemoryRepairScheduleDao(this, memoryRepairUnitDao);
  private final MemoryEventsDao memEventsDao = new MemoryEventsDao(this);
  private final MemoryClusterDao memClusterDao = new MemoryClusterDao(
      this,
      memoryRepairUnitDao,
      memoryRepairRunDao,
      memRepairScheduleDao,
      memEventsDao
  );
  private final MemorySnapshotDao memSnapshotDao = new MemorySnapshotDao();
  private final MemoryMetricsDao memMetricsDao = new MemoryMetricsDao();

  public MemoryStorageFacade(String persistenceStoragePath) {
    LOG.info("Using memory storage backend. Persistence storage path: {}", persistenceStoragePath);
    this.embeddedStorage = EmbeddedStorage.start(Paths.get(persistenceStoragePath));
    if (this.embeddedStorage.root() == null) {
      LOG.info("Creating new data storage");
      this.memoryStorageRoot = new MemoryStorageRoot();
      this.embeddedStorage.setRoot(this.memoryStorageRoot);
    } else {
      LOG.info("Loading existing data from persistence storage");
      this.memoryStorageRoot = (MemoryStorageRoot) this.embeddedStorage.root();
      LOG.info("Loaded {} clusters: {}",
          this.memoryStorageRoot.getClusters().size(), this.memoryStorageRoot.getClusters().keySet());
      this.memoryStorageRoot.getClusters().entrySet().stream().forEach(entry -> {
        Cluster cluster = entry.getValue();
        LOG.info("Loaded cluster: {} / seeds: {}", cluster.getName(), cluster.getSeedHosts());
      });
      LOG.info("MemoryStorageRoot: {}", this.memoryStorageRoot);
    }
  }

  public MemoryStorageFacade() {
    this("/tmp/" + UUID.randomUUID().toString());
  }

  @Override
  public boolean isStorageConnected() {
    // Just assuming the MemoryStorage is always functional when instantiated.
    return true;
  }

  private boolean addClusterAssertions(Cluster cluster) {
    return this.memClusterDao.addClusterAssertions(cluster);
  }

  @Override
  public List<PercentRepairedMetric> getPercentRepairedMetrics(String clusterName, UUID repairScheduleId, Long since) {
    return this.memMetricsDao.getPercentRepairedMetrics(clusterName, repairScheduleId, since);
  }

  @Override
  public void storePercentRepairedMetric(PercentRepairedMetric metric) {
    this.memMetricsDao.storePercentRepairedMetric(metric);
  }

  @Override
  public void start() {
    // no-op
  }

  @Override
  public void stop() {
    this.embeddedStorage.shutdown();
  }

  @Override
  public IEventsDao getEventsDao() {
    return this.memEventsDao;
  }

  @Override
  public ISnapshotDao getSnapshotDao() {
    return this.memSnapshotDao;
  }

  @Override
  public IRepairRunDao getRepairRunDao() {
    return this.memoryRepairRunDao;
  }

  @Override
  public IRepairSegmentDao getRepairSegmentDao() {
    return this.memRepairSegment;
  }

  @Override
  public IRepairUnitDao getRepairUnitDao() {
    return this.memoryRepairUnitDao;
  }

  @Override
  public IRepairScheduleDao getRepairScheduleDao() {
    return this.memRepairScheduleDao;
  }

  @Override
  public IClusterDao getClusterDao() {
    return this.memClusterDao;
  }

  private void persist(Object... objects) {
    this.embeddedStorage.storeAll(objects);
    this.embeddedStorage.storeRoot();
  }

  // Cluster operations
  public Map<String, Cluster> getClusters() {
    return this.memoryStorageRoot.getClusters();
  }

  public Cluster addCluster(Cluster cluster) {
    Cluster newCluster = this.memoryStorageRoot.addCluster(cluster);
    this.persist(memoryStorageRoot.getClusters());
    return newCluster;
  }

  public Cluster removeCluster(String clusterName) {
    Cluster cluster =  this.memoryStorageRoot.removeCluster(clusterName);
    this.persist(memoryStorageRoot.getClusters());
    return cluster;
  }

  // RepairSchedule operations
  public RepairSchedule addRepairSchedule(RepairSchedule schedule) {
    RepairSchedule newSchedule = this.memoryStorageRoot.addRepairSchedule(schedule);
    this.persist(this.memoryStorageRoot.getRepairSchedules());
    return newSchedule;
  }

  public RepairSchedule removeRepairSchedule(UUID id) {
    RepairSchedule schedule = this.memoryStorageRoot.removeRepairSchedule(id);
    this.persist(this.memoryStorageRoot.getRepairSchedules());
    return schedule;
  }

  public Optional<RepairSchedule> getRepairScheduleById(UUID id) {
    return Optional.ofNullable(this.memoryStorageRoot.getRepairScheduleById(id));
  }

  public Collection<RepairSchedule> getRepairSchedules() {
    return this.memoryStorageRoot.getRepairSchedules().values();
  }

  // RepairRun operations
  public Collection<RepairRun> getRepairRuns() {
    return this.memoryStorageRoot.getRepairRuns().values();
  }

  public RepairRun addRepairRun(RepairRun run) {
    RepairRun newRun = this.memoryStorageRoot.addRepairRun(run);
    this.persist(this.memoryStorageRoot.getRepairRuns());
    return newRun;
  }

  public RepairRun removeRepairRun(UUID id) {
    RepairRun run = this.memoryStorageRoot.removeRepairRun(id);
    this.persist(this.memoryStorageRoot.getRepairRuns());
    return run;
  }

  public Optional<RepairRun> getRepairRunById(UUID id) {
    return Optional.ofNullable(this.memoryStorageRoot.getRepairRunById(id));
  }

  // RepairUnit operations
  public Collection<RepairUnit> getRepairUnits() {
    return this.memoryStorageRoot.getRepairUnits().values();
  }

  public RepairUnit addRepairUnit(Optional<RepairUnit.Builder> key, RepairUnit unit) {
    RepairUnit newUnit = this.memoryStorageRoot.addRepairUnit(key.get(), unit);
    this.persist(this.memoryStorageRoot.getRepairUnits(), this.memoryStorageRoot.getRepairUnitsByKey());
    return newUnit;
  }

  public RepairUnit removeRepairUnit(Optional<RepairUnit.Builder> key, UUID id) {
    RepairUnit unit = this.memoryStorageRoot.removeRepairUnit(key.get(), id);
    this.persist(this.memoryStorageRoot.getRepairUnits(), this.memoryStorageRoot.getRepairUnitsByKey());
    return unit;
  }

  public RepairUnit getRepairUnitById(UUID id) {
    return this.memoryStorageRoot.getrRepairUnitById(id);
  }

  public RepairUnit getRepairUnitByKey(RepairUnit.Builder key) {
    return this.memoryStorageRoot.getRepairUnitByKey(key);
  }

  // RepairSegment operations
  public RepairSegment addRepairSegment(RepairSegment segment) {
    final RepairSegment newSegment = this.memoryStorageRoot.addRepairSegment(segment);
    // also add the segment by RunId
    UUID repairSegmentRunId = segment.getRunId();
    LinkedHashMap<UUID, RepairSegment> segmentsByRunId =
        this.memoryStorageRoot.getRepairSegmentsByRunId().get(repairSegmentRunId);
    if (segmentsByRunId == null) {
      segmentsByRunId = new LinkedHashMap<UUID, RepairSegment>();
      this.memoryStorageRoot.getRepairSegmentsByRunId().put(repairSegmentRunId, segmentsByRunId);
    }
    segmentsByRunId.put(segment.getId(), segment);
    this.persist(this.memoryStorageRoot.getRepairSegments(), this.memoryStorageRoot.getRepairSegmentsByRunId());
    return newSegment;
  }

  public RepairSegment removeRepairSegment(UUID id) {
    RepairSegment segment = this.memoryStorageRoot.removeRepairSegment(id);
    // also remove the segment from the byRunId map
    UUID repairSegmentRunId = segment.getRunId();
    LinkedHashMap<UUID, RepairSegment> segmentsByRunId =
        this.memoryStorageRoot.getRepairSegmentsByRunId().get(repairSegmentRunId);
    if (segmentsByRunId != null) {
      segmentsByRunId.remove(segment.getId());
    }
    this.persist(this.memoryStorageRoot.getRepairSegments(), this.memoryStorageRoot.getRepairSegmentsByRunId());
    return segment;
  }

  public RepairSegment getRepairSegmentById(UUID id) {
    return this.memoryStorageRoot.getRepairSegmentById(id);
  }

  public Collection<RepairSegment> getRepairSegmentsByRunId(UUID runId) {
    if (this.memoryStorageRoot.getRepairSegmentsByRunId().containsKey(runId)) {
      return this.memoryStorageRoot.getRepairSegmentsByRunId().get(runId).values();
    }
    return Collections.EMPTY_LIST;
  }

  // RepairSubscription operations
  public Map<UUID, DiagEventSubscription> getSubscriptionsById() {
    return this.memoryStorageRoot.getSubscriptionsById();
  }
}

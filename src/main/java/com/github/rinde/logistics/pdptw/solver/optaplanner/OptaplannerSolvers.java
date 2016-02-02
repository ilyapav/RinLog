/*
 * Copyright (C) 2013-2016 Rinde van Lon, iMinds-DistriNet, KU Leuven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.rinde.logistics.pdptw.solver.optaplanner;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Verify.verify;
import static com.google.common.base.Verify.verifyNotNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.CheckReturnValue;
import javax.annotation.Nullable;
import javax.measure.quantity.Duration;
import javax.measure.quantity.Length;
import javax.measure.quantity.Velocity;
import javax.measure.unit.NonSI;
import javax.measure.unit.SI;
import javax.measure.unit.Unit;

import org.optaplanner.benchmark.api.PlannerBenchmark;
import org.optaplanner.benchmark.api.PlannerBenchmarkFactory;
import org.optaplanner.benchmark.impl.PlannerBenchmarkRunner;
import org.optaplanner.benchmark.impl.result.SolverBenchmarkResult;
import org.optaplanner.core.api.score.buildin.hardsoftlong.HardSoftLongScore;
import org.optaplanner.core.api.solver.SolverFactory;
import org.optaplanner.core.api.solver.event.BestSolutionChangedEvent;
import org.optaplanner.core.api.solver.event.SolverEventListener;
import org.optaplanner.core.config.score.definition.ScoreDefinitionType;
import org.optaplanner.core.config.score.director.ScoreDirectorFactoryConfig;
import org.optaplanner.core.config.solver.EnvironmentMode;
import org.optaplanner.core.config.solver.SolverConfig;
import org.optaplanner.core.config.solver.random.RandomType;
import org.optaplanner.core.config.solver.termination.TerminationConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.rinde.logistics.pdptw.solver.optaplanner.ParcelVisit.VisitType;
import com.github.rinde.rinsim.central.GlobalStateObject;
import com.github.rinde.rinsim.central.GlobalStateObject.VehicleStateObject;
import com.github.rinde.rinsim.central.GlobalStateObjects;
import com.github.rinde.rinsim.central.Solver;
import com.github.rinde.rinsim.central.SolverValidator;
import com.github.rinde.rinsim.central.Solvers;
import com.github.rinde.rinsim.central.rt.RealtimeSolver;
import com.github.rinde.rinsim.central.rt.Scheduler;
import com.github.rinde.rinsim.core.model.pdp.Parcel;
import com.github.rinde.rinsim.pdptw.common.StatisticsDTO;
import com.github.rinde.rinsim.scenario.gendreau06.Gendreau06ObjectiveFunction;
import com.github.rinde.rinsim.util.StochasticSupplier;
import com.google.auto.value.AutoValue;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

/**
 *
 * @author Rinde van Lon
 */
public final class OptaplannerSolvers {
  static final Logger LOGGER =
    LoggerFactory.getLogger(OptaplannerSolvers.class);

  static final Unit<Duration> TIME_UNIT = SI.MILLI(SI.SECOND);
  static final Unit<Velocity> SPEED_UNIT = NonSI.KILOMETERS_PER_HOUR;
  static final Unit<Length> DISTANCE_UNIT = SI.KILOMETER;

  // this part of a while loop
  static final long WAIT_FOR_SOLVER_TERMINATION_PERIOD_MS = 5L;

  private OptaplannerSolvers() {}

  @CheckReturnValue
  public static Builder builder() {
    return Builder.defaultInstance();
  }

  public static Map<String, SolverConfig> getConfigsFromBenchmark(
      String xmlLocation) {
    final PlannerBenchmarkFactory plannerBenchmarkFactory =
      PlannerBenchmarkFactory.createFromFreemarkerXmlResource(xmlLocation);
    final PlannerBenchmark plannerBenchmark =
      plannerBenchmarkFactory.buildPlannerBenchmark();

    final PlannerBenchmarkRunner pbr =
      (PlannerBenchmarkRunner) plannerBenchmark;

    final ImmutableMap.Builder<String, SolverConfig> builder =
      ImmutableMap.builder();
    for (final SolverBenchmarkResult sbr : pbr.getPlannerBenchmarkResult()
      .getSolverBenchmarkResultList()) {
      builder.put(sbr.getName().replaceAll(" ", "-"), sbr.getSolverConfig());
    }
    return builder.build();
  }

  @CheckReturnValue
  public static PDPSolution convert(GlobalStateObject state) {
    checkArgument(state.getTimeUnit().equals(TIME_UNIT));
    checkArgument(state.getSpeedUnit().equals(SPEED_UNIT));
    checkArgument(state.getDistUnit().equals(DISTANCE_UNIT));

    final PDPSolution problem = new PDPSolution(state.getTime());

    // final Set<Parcel> parcels = GlobalStateObjects.allParcels(state);

    final List<ParcelVisit> parcelList = new ArrayList<>();
    final Map<Parcel, ParcelVisit> pickups = new LinkedHashMap<>();
    final Map<Parcel, ParcelVisit> deliveries = new LinkedHashMap<>();

    for (final Parcel p : state.getAvailableParcels()) {
      final ParcelVisit pickup = new ParcelVisit(p, VisitType.PICKUP);
      final ParcelVisit delivery = new ParcelVisit(p, VisitType.DELIVER);
      pickups.put(p, pickup);
      deliveries.put(p, delivery);
      pickup.setAssociation(delivery);
      delivery.setAssociation(pickup);
      parcelList.add(pickup);
      parcelList.add(delivery);
    }

    boolean firstVehicle = true;

    final List<Vehicle> vehicleList = new ArrayList<>();
    for (int i = 0; i < state.getVehicles().size(); i++) {
      final VehicleStateObject vso = state.getVehicles().get(i);
      final Vehicle vehicle = new Vehicle(vso, i);
      vehicleList.add(vehicle);

      final List<ParcelVisit> visits = new ArrayList<>();
      if (vso.getRoute().isPresent()) {
        List<Parcel> route = vso.getRoute().get();

        if (firstVehicle) {
          route = new ArrayList<>(route);
          final Set<Parcel> unassigned =
            GlobalStateObjects.unassignedParcels(state);
          route.addAll(unassigned);
          route.addAll(unassigned);
          firstVehicle = false;
        }

        for (final Parcel p : route) {
          // is it a pickup or a delivery?
          if (vso.getContents().contains(p) || !pickups.containsKey(p)) {
            ParcelVisit delivery;
            if (deliveries.containsKey(p)) {
              delivery = deliveries.remove(p);
            } else {
              delivery = new ParcelVisit(p, VisitType.DELIVER);
              parcelList.add(delivery);
            }
            visits.add(delivery);
          } else {
            visits.add(checkNotNull(pickups.remove(p)));
          }
        }
      } else {
        throw new IllegalArgumentException();
        // add destination
        //
        // final List<Parcel> route = new ArrayList<>();
        // route.addAll(vso.getDestination().asSet());

        // add contents
      }
      initRoute(vehicle, visits);
    }

    problem.parcelList = parcelList;
    problem.vehicleList = vehicleList;

    // System.out.println("**** INITIAL ****");
    // System.out.println(problem);

    return problem;
  }

  static org.optaplanner.core.api.solver.Solver createOptaplannerSolver(
      Builder builder, long seed) {

    final SolverFactory factory;
    if (builder.getSolverConfig() != null) {
      factory = SolverFactory.createEmpty();
      factory.getSolverConfig().inherit(builder.getSolverConfig());
    } else {
      factory =
        SolverFactory.createFromXmlResource(builder.getSolverXmlResource());
    }
    final SolverConfig config = factory.getSolverConfig();
    config.setEntityClassList(
      ImmutableList.<Class<?>>of(
        ParcelVisit.class,
        Visit.class));
    config.setSolutionClass(PDPSolution.class);

    final TerminationConfig terminationConfig = new TerminationConfig();
    terminationConfig
      .setUnimprovedMillisecondsSpentLimit(builder.getUnimprovedMsLimit());
    config.setTerminationConfig(terminationConfig);

    final ScoreDirectorFactoryConfig scoreConfig =
      new ScoreDirectorFactoryConfig();
    scoreConfig.setScoreDefinitionType(ScoreDefinitionType.HARD_SOFT_LONG);
    scoreConfig.setIncrementalScoreCalculatorClass(ScoreCalculator.class);
    config.setScoreDirectorFactoryConfig(scoreConfig);

    config.setRandomSeed(seed);
    config.setRandomType(RandomType.MERSENNE_TWISTER);
    config.setEnvironmentMode(
      builder.isValidated() ? EnvironmentMode.FULL_ASSERT
        : EnvironmentMode.REPRODUCIBLE);

    return factory.buildSolver();
  }

  static ImmutableList<ImmutableList<Parcel>> toSchedule(PDPSolution solution) {
    final ImmutableList.Builder<ImmutableList<Parcel>> scheduleBuilder =
      ImmutableList.builder();
    for (final Vehicle v : solution.vehicleList) {
      final ImmutableList.Builder<Parcel> routeBuilder =
        ImmutableList.builder();
      ParcelVisit pv = v.getNextVisit();
      while (pv != null) {
        routeBuilder.add(pv.getParcel());
        pv = pv.getNextVisit();
      }
      scheduleBuilder.add(routeBuilder.build());
    }
    return scheduleBuilder.build();
  }

  static void initRoute(Vehicle vehicle, List<ParcelVisit> visits) {
    final Visit last = vehicle.getLastVisit();
    // attach to tail
    Visit prev = last == null ? vehicle : last;
    for (final ParcelVisit pv : visits) {
      pv.setPreviousVisit(prev);
      pv.setVehicle(vehicle);
      prev.setNextVisit(pv);
      prev = pv;
    }
  }

  @AutoValue
  public abstract static class Builder {

    static final String DEFAULT_SOLVER_XML_RESOURCE =
      "com/github/rinde/logistics/pdptw/solver/optaplanner/solverConfig.xml";

    Builder() {}

    abstract boolean isValidated();

    abstract Gendreau06ObjectiveFunction getObjectiveFunction();

    abstract long getUnimprovedMsLimit();

    abstract String getSolverXmlResource();

    @Nullable
    abstract SolverConfig getSolverConfig();

    @Nullable
    abstract String getName();

    @CheckReturnValue
    public Builder withValidated(boolean validate) {
      return create(validate, getObjectiveFunction(),
        getUnimprovedMsLimit(), getSolverXmlResource(), getSolverConfig(),
        getName());
    }

    @CheckReturnValue
    public Builder withObjectiveFunction(Gendreau06ObjectiveFunction func) {
      return create(isValidated(), func, getUnimprovedMsLimit(),
        getSolverXmlResource(), getSolverConfig(), getName());
    }

    @CheckReturnValue
    public Builder withUnimprovedMsLimit(long ms) {
      return create(isValidated(), getObjectiveFunction(), ms,
        getSolverXmlResource(), getSolverConfig(), getName());
    }

    @CheckReturnValue
    public Builder withSolverXmlResource(String solverXmlResource) {
      return create(isValidated(), getObjectiveFunction(),
        getUnimprovedMsLimit(), solverXmlResource, getSolverConfig(),
        getName());
    }

    // takes precedence over xml
    @CheckReturnValue
    public Builder withSolverConfig(SolverConfig solverConfig) {
      return create(isValidated(), getObjectiveFunction(),
        getUnimprovedMsLimit(), getSolverXmlResource(), solverConfig,
        getName());
    }

    // mandatory, used to identify solver in logs/gui
    public Builder withName(String name) {
      return create(isValidated(), getObjectiveFunction(),
        getUnimprovedMsLimit(), getSolverXmlResource(), getSolverConfig(),
        name);
    }

    @CheckReturnValue
    public StochasticSupplier<Solver> buildSolverSupplier() {
      checkPreconditions();
      return new SimulatedTimeSupplier(this);
    }

    @CheckReturnValue
    public StochasticSupplier<RealtimeSolver> buildRealtimeSolverSupplier() {
      checkPreconditions();
      return new RealtimeSupplier(this);
    }

    void checkPreconditions() {
      checkArgument(getName() != null);
    }

    static Builder defaultInstance() {
      return create(false, Gendreau06ObjectiveFunction.instance(), 1L,
        DEFAULT_SOLVER_XML_RESOURCE, null, null);
    }

    static Builder create(boolean validate, Gendreau06ObjectiveFunction func,
        long sec, String resource, @Nullable SolverConfig config,
        @Nullable String name) {
      return new AutoValue_OptaplannerSolvers_Builder(validate, func, sec,
        resource, config, name);
    }
  }

  static class OptaplannerSolver implements Solver {
    @Nullable
    PDPSolution lastSolution;
    final ScoreCalculator scoreCalculator;

    private final org.optaplanner.core.api.solver.Solver solver;
    private final String name;
    private long lastSoftScore;

    OptaplannerSolver(Builder builder, long seed) {
      solver = createOptaplannerSolver(builder, seed);
      scoreCalculator = new ScoreCalculator();
      lastSolution = null;
      name = "Optaplanner-" + verifyNotNull(builder.getName());
    }

    @Override
    public ImmutableList<ImmutableList<Parcel>> solve(GlobalStateObject state)
        throws InterruptedException {
      final ImmutableList<ImmutableList<Parcel>> sol = doSolve(state);

      checkState(sol != null,
        "Optaplanner didn't find a solution satisfying all hard constraints.");

      final PDPSolution solution = (PDPSolution) solver.getBestSolution();
      final HardSoftLongScore score = solution.getScore();
      lastSolution = solution;
      lastSoftScore = score.getSoftScore();
      return toSchedule(solution);
    }

    // actual solving, returns null when no valid solution was found
    @Nullable
    public ImmutableList<ImmutableList<Parcel>> doSolve(GlobalStateObject state)
        throws InterruptedException {
      final PDPSolution problem = convert(state);
      solver.solve(problem);

      final PDPSolution solution = (PDPSolution) solver.getBestSolution();
      final HardSoftLongScore score = solution.getScore();
      if (score.getHardScore() != 0) {
        return null;
      }
      return toSchedule(solution);
    }

    void addEventListener(SolverEventListener<PDPSolution> listener) {
      solver.addEventListener(listener);
    }

    boolean isSolving() {
      return solver.isSolving();
    }

    boolean isTerminateEarly() {
      return solver.isTerminateEarly();
    }

    void terminateEarly() {
      solver.terminateEarly();
    }

    @VisibleForTesting
    long getSoftScore() {
      return lastSoftScore;
    }

    @Override
    public String toString() {
      return name;
    }
  }

  static class OptaplannerRTSolver implements RealtimeSolver {
    final OptaplannerSolver solver;
    final AtomicBoolean permissionToRun;
    Optional<Scheduler> scheduler;
    @Nullable
    GlobalStateObject lastSnapshot;
    private final String name;

    OptaplannerRTSolver(Builder b, long seed) {
      solver = new OptaplannerSolver(b, seed);
      scheduler = Optional.absent();
      name = "OptaplannerRT-" + verifyNotNull(b.getName());
      permissionToRun = new AtomicBoolean(false);
    }

    @Override
    public void init(final Scheduler sched) {
      LOGGER.trace("OptaplannerRTSolver.init: {}", name);
      checkState(!scheduler.isPresent(),
        "Solver can be initialized only once.");
      scheduler = Optional.of(sched);

      solver.addEventListener(new SolverEventListener<PDPSolution>() {
        @Override
        public void bestSolutionChanged(
            @SuppressWarnings("null") BestSolutionChangedEvent<PDPSolution> event) {
          if (event.isNewBestSolutionInitialized()
            && event.getNewBestSolution().getScore().getHardScore() == 0) {
            final ImmutableList<ImmutableList<Parcel>> schedule =
              toSchedule(event.getNewBestSolution());

            LOGGER.info("Found new best solution, update schedule. {}",
              solver.isSolving());
            sched.updateSchedule(verifyNotNull(lastSnapshot), schedule);
          }
        }
      });
    }

    @Override
    public synchronized void problemChanged(final GlobalStateObject snapshot) {
      permissionToRun.set(true);
      start(snapshot);
    }

    @Override
    public synchronized void receiveSnapshot(GlobalStateObject snapshot) {
      // this is the snapshot the solver is currently using
      final GlobalStateObject last = lastSnapshot;
      if (last == null) {
        return;
      }
      // if something significant happens -> restart solver
      boolean significantChangeDetected = false;
      for (int i = 0; i < snapshot.getVehicles().size(); i++) {
        // when a vehicle has a destination, it has committed to perform a
        // specific service operation, this has implications for the schedule:
        // this specific order can no longer be exchanged with other vehicles.
        // Therefore, when this is detected we want to restart the solver such
        // that it won't waste time trying to optimize based on outdated
        // assumptions. Note that we are only interested in events where a
        // vehicle takes upon a *new* commitment (not when it is finished with
        // an old commitment).
        if (snapshot.getVehicles().get(i).getDestination().isPresent()
          && !last.getVehicles().get(i).getDestination()
            .equals(snapshot.getVehicles().get(i).getDestination())) {
          significantChangeDetected = true;
          break;
        }
      }
      if (significantChangeDetected) {
        LOGGER.info(
          "Vehicle destination commitment change detected -> restart solver.");
        start(snapshot);
      }
    }

    @Override
    public synchronized void cancel() {
      permissionToRun.set(false);
      doCancel();
    }

    synchronized void start(final GlobalStateObject snapshot) {
      checkState(scheduler.isPresent());
      doCancel();
      if (!permissionToRun.get()) {
        LOGGER.info("No permission to continue, not starting new computation.");
        return;
      }
      lastSnapshot = snapshot;
      LOGGER.info("Start RT Optaplanner Solver");
      final ListenableFuture<ImmutableList<ImmutableList<Parcel>>> future =
        scheduler.get().getSharedExecutor()
          .submit(new OptaplannerCallable(solver, snapshot));

      Futures.addCallback(future,
        new FutureCallback<ImmutableList<ImmutableList<Parcel>>>() {

          @Override
          public void onSuccess(
              @Nullable ImmutableList<ImmutableList<Parcel>> result) {
            if (result == null) {
              if (solver.isTerminateEarly()) {
                LOGGER.info(
                  "Solver was terminated early and didn't have enough time to "
                    + "find a valid solution.");
              } else {
                scheduler.get().reportException(
                  new IllegalArgumentException("Solver.solve(..) must return a "
                    + "non-null result. Solver: " + solver));
              }
            } else {
              LOGGER.info("Computations stopped, update schedule.");
              scheduler.get().updateSchedule(snapshot, result);

              if (permissionToRun.get() && solver.isTerminateEarly()) {
                LOGGER.info(" > continue after restart.");
              } else {
                LOGGER.info(" > done for now.");
                scheduler.get().doneForNow();
              }
            }
          }

          @Override
          public void onFailure(Throwable t) {
            if (t instanceof CancellationException) {
              return;
            }
            scheduler.get().reportException(t);
          }
        });
    }

    synchronized void doCancel() {
      if (solver.isSolving()) {
        LOGGER.info("Terminate early");
        solver.terminateEarly();
        while (solver.isSolving()) {
          try {
            Thread.sleep(WAIT_FOR_SOLVER_TERMINATION_PERIOD_MS);
          } catch (final InterruptedException e) {
            LOGGER.warn("Interrupt while waiting for solver termination.");
            // stop waiting upon interrupt
            break;
          }
        }
        LOGGER.info("Solver terminated early.");
      }
    }

    @Override
    public synchronized boolean isComputing() {
      return solver.isSolving() || permissionToRun.get();
    }

    @Override
    public String toString() {
      return name;
    }
  }

  static class OptaplannerCallable
      implements Callable<ImmutableList<ImmutableList<Parcel>>> {

    final OptaplannerSolver solver;
    final GlobalStateObject state;

    OptaplannerCallable(OptaplannerSolver solv, GlobalStateObject st) {
      solver = solv;
      state = st;
    }

    @Nullable
    @Override
    public ImmutableList<ImmutableList<Parcel>> call() throws Exception {
      verify(!solver.isSolving(), "Solver is already solving, this is a bug.");
      return solver.doSolve(state);
    }

  }

  static class SimulatedTimeSupplier implements StochasticSupplier<Solver> {
    final Builder builder;

    SimulatedTimeSupplier(Builder b) {
      builder = b;
    }

    @Override
    public Solver get(long seed) {
      if (builder.isValidated()) {
        return new Validator(builder, seed);
      }
      return new OptaplannerSolver(builder, seed);
    }

    @Override
    public String toString() {
      return "OptaplannerST";
    }
  }

  static class RealtimeSupplier implements StochasticSupplier<RealtimeSolver> {
    final Builder builder;

    RealtimeSupplier(Builder b) {
      builder = b;
    }

    @Override
    public RealtimeSolver get(long seed) {
      return new OptaplannerRTSolver(builder, seed);
    }

    @Override
    public String toString() {
      return "OptaplannerRT";
    }
  }

  static class Validator implements Solver {

    static final double MAX_SPEED_DIFF = .001;
    static final double SIXTY_SEC_IN_NS = 60000000000d;
    static final double TEN_SEC_IN_NS = 10000000000d;

    final OptaplannerSolver solver;
    Builder builder;

    Validator(Builder b, long seed) {
      solver = new OptaplannerSolver(b, seed);
      builder = b;
    }

    @Override
    public ImmutableList<ImmutableList<Parcel>> solve(GlobalStateObject state)
        throws InterruptedException {
      checkState(Math.abs(state.getVehicles().get(0).getDto().getSpeed()
        - builder.getObjectiveFunction().getVehicleSpeed()) < MAX_SPEED_DIFF);

      final ImmutableList<ImmutableList<Parcel>> schedule = solver.solve(state);

      SolverValidator.validateOutputs(schedule, state);

      System.out.println(state);
      System.out.println("new schedule");
      System.out.println(Joiner.on("\n").join(schedule));

      final StatisticsDTO stats = Solvers.computeStats(state, schedule);

      // convert cost to nanosecond precision
      final double cost = builder.getObjectiveFunction().computeCost(stats)
        * SIXTY_SEC_IN_NS;

      final ScoreCalculator sc = solver.scoreCalculator;

      sc.resetWorkingSolution(solver.lastSolution);

      System.out.println(" === RinSim ===");
      System.out.println(
        builder.getObjectiveFunction().printHumanReadableFormat(stats));
      System.out.println(" === Optaplanner ===");
      System.out
        .println("Travel time: " + sc.getTravelTime() / SIXTY_SEC_IN_NS);
      System.out.println("Tardiness: " + sc.getTardiness() / SIXTY_SEC_IN_NS);
      System.out.println("Overtime: " + sc.getOvertime() / SIXTY_SEC_IN_NS);
      System.out.println(
        "Total: " + sc.calculateScore().getSoftScore() / -SIXTY_SEC_IN_NS);

      // optaplanner has nanosecond precision
      final double optaplannerCost = solver.getSoftScore() * -1d;

      final double difference = Math.abs(cost - optaplannerCost);
      // max 10 nanosecond deviation is allowed
      checkState(difference < TEN_SEC_IN_NS,
        "ObjectiveFunction cost (%s) must be equal to Optaplanner cost (%s),"
          + " the difference is %s.",
        cost, optaplannerCost, difference);

      return schedule;
    }
  }
}
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
package com.github.rinde.logistics.pdptw.mas.route;

import static com.google.common.collect.Lists.newLinkedList;

import java.util.Queue;
import java.util.Set;

import com.github.rinde.rinsim.central.GlobalStateObject;
import com.github.rinde.rinsim.central.SimSolver;
import com.github.rinde.rinsim.central.SimSolverBuilder;
import com.github.rinde.rinsim.central.Solver;
import com.github.rinde.rinsim.central.SolverUser;
import com.github.rinde.rinsim.central.SolverValidator;
import com.github.rinde.rinsim.central.Solvers.SolveArgs;
import com.github.rinde.rinsim.core.model.pdp.PDPModel.VehicleState;
import com.github.rinde.rinsim.core.model.pdp.Parcel;
import com.github.rinde.rinsim.util.StochasticSupplier;
import com.github.rinde.rinsim.util.StochasticSuppliers;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;

/**
 * A {@link RoutePlanner} implementation that uses a {@link Solver} that
 * computes a complete route each time {@link #update(Set, long)} is called.
 * @author Rinde van Lon
 */
public class SolverRoutePlanner
    extends AbstractRoutePlanner
    implements SolverUser {

  private final Solver solver;
  private Queue<? extends Parcel> route;
  private Optional<SimSolver> solverHandle;
  private Optional<SimSolverBuilder> solverBuilder;
  private final boolean reuseCurRoutes;

  /**
   * Create a route planner that uses the specified {@link Solver} to compute
   * the best route.
   * @param s {@link Solver} used for route planning.
   * @param reuseCurrentRoutes Whether to reuse the current routes.
   */
  public SolverRoutePlanner(Solver s, boolean reuseCurrentRoutes) {
    solver = s;
    route = newLinkedList();
    solverHandle = Optional.absent();
    solverBuilder = Optional.absent();
    reuseCurRoutes = reuseCurrentRoutes;
  }

  /**
   * Calling this method overrides the route of this planner. This method has
   * similar effect as {@link #update(Set, long)} except that no computations
   * are done.
   * @param r The new route.
   */
  public void changeRoute(Iterable<? extends Parcel> r) {
    updated = true;
    route = newLinkedList(r);
  }

  @Override
  protected void doUpdate(Set<Parcel> onMap, long time) {
    if (onMap.isEmpty()
      && pdpModel.get().getContents(vehicle.get()).isEmpty()) {
      route.clear();
    } else {
      LOGGER.info("vehicle {}", pdpModel.get().getVehicleState(vehicle.get()));
      if (pdpModel.get().getVehicleState(vehicle.get()) != VehicleState.IDLE) {
        LOGGER.info("parcel {} {}",
          pdpModel.get().getVehicleActionInfo(vehicle.get())
            .getParcel(),

          pdpModel.get().getParcelState(
            pdpModel.get().getVehicleActionInfo(vehicle.get())
              .getParcel()));
      }

      final SolveArgs args = SolveArgs.create().useParcels(onMap);
      if (reuseCurRoutes) {
        args.useCurrentRoutes(ImmutableList.of(ImmutableList.copyOf(route)));
        try {
          final GlobalStateObject gso = solverHandle.get().convert(args);
          LOGGER.info("destination {} available: {}",
            gso.getVehicles().get(0).getDestination(),
            gso.getAvailableParcels());

          SolverValidator.checkRoute(gso.getVehicles().get(0), 0);
        } catch (final IllegalArgumentException e) {
          args.noCurrentRoutes();
        }
      }
      route = newLinkedList(solverHandle.get().solve(args).get(0));
    }
    LOGGER.info("{}", pdpModel.get().getVehicleState(vehicle.get()));
    dispatchChangeEvent();
  }

  @Override
  public boolean hasNext() {
    return !route.isEmpty();
  }

  @Override
  public Optional<Parcel> current() {
    return Optional.fromNullable((Parcel) route.peek());
  }

  @Override
  public Optional<ImmutableList<Parcel>> currentRoute() {
    if (route.isEmpty()) {
      return Optional.absent();
    }
    return Optional.of(ImmutableList.copyOf(route));
  }

  @Override
  protected void nextImpl(long time) {
    route.poll();
  }

  @Override
  public void setSolverProvider(SimSolverBuilder builder) {
    solverBuilder = Optional.of(builder);
    afterInit();
  }

  @Override
  public void afterInit() {
    if (solverBuilder.isPresent() && vehicle.isPresent()) {
      solverHandle =
        Optional.of(solverBuilder.get().setVehicles(vehicle.asSet())
          .build(solver));
    }
  }

  /**
   * Supplier for {@link SolverRoutePlanner} that does not reuse the current
   * routes.
   * @param solverSupplier A {@link StochasticSupplier} that supplies the
   *          {@link Solver} that will be used in the {@link SolverRoutePlanner}
   *          .
   * @return A {@link StochasticSupplier} that supplies
   *         {@link SolverRoutePlanner} instances.
   */
  public static StochasticSupplier<SolverRoutePlanner> supplierWithoutCurrentRoutes(
      final StochasticSupplier<? extends Solver> solverSupplier) {
    return new SRPSupplier(solverSupplier, false);
  }

  /**
   * @param solverSupplier A {@link StochasticSupplier} that supplies the
   *          {@link Solver} that will be used in the {@link SolverRoutePlanner}
   *          .
   * @return A {@link StochasticSupplier} that supplies
   *         {@link SolverRoutePlanner} instances.
   */
  public static StochasticSupplier<SolverRoutePlanner> supplier(
      final StochasticSupplier<? extends Solver> solverSupplier) {
    return new SRPSupplier(solverSupplier, true);
  }

  private static class SRPSupplier extends
      StochasticSuppliers.AbstractStochasticSupplier<SolverRoutePlanner> {
    private static final long serialVersionUID = -5592714216595546915L;
    final StochasticSupplier<? extends Solver> solverSupplier;
    final boolean reuseCurrentRoutes;

    SRPSupplier(final StochasticSupplier<? extends Solver> ss,
        final boolean rr) {
      solverSupplier = ss;
      reuseCurrentRoutes = rr;
    }

    @Override
    public SolverRoutePlanner get(long seed) {
      return new SolverRoutePlanner(solverSupplier.get(seed),
        reuseCurrentRoutes);
    }

    @Override
    public String toString() {
      return super.toString() + "-" + solverSupplier.toString();
    }
  }
}

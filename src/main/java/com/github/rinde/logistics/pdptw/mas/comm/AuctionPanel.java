/*
 * Copyright (C) 2013-2015 Rinde van Lon, iMinds DistriNet, KU Leuven
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
package com.github.rinde.logistics.pdptw.mas.comm;

import java.util.LinkedHashMap;
import java.util.Map;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeColumn;
import org.eclipse.swt.widgets.TreeItem;
import org.joda.time.Period;
import org.joda.time.format.PeriodFormatter;
import org.joda.time.format.PeriodFormatterBuilder;

import com.github.rinde.logistics.pdptw.mas.comm.AuctionCommModel.AuctionEvent;
import com.github.rinde.logistics.pdptw.mas.comm.AuctionCommModel.EventType;
import com.github.rinde.rinsim.core.model.DependencyProvider;
import com.github.rinde.rinsim.core.model.Model.AbstractModel;
import com.github.rinde.rinsim.core.model.ModelBuilder.AbstractModelBuilder;
import com.github.rinde.rinsim.core.model.pdp.Parcel;
import com.github.rinde.rinsim.event.Event;
import com.github.rinde.rinsim.event.Listener;
import com.github.rinde.rinsim.ui.renderers.PanelRenderer;
import com.google.auto.value.AutoValue;
import com.google.common.base.Optional;

/**
 *
 * @author Rinde van Lon
 */
public class AuctionPanel
    extends AbstractModel<Parcel>
    implements PanelRenderer {
  static final String SPACE = " ";
  static final String TIME_SEPARATOR = ":";

  static final PeriodFormatter FORMATTER = new PeriodFormatterBuilder()
      .appendDays()
      .appendSeparator(SPACE)
      .minimumPrintedDigits(2)
      .printZeroAlways()
      .appendHours()
      .appendLiteral(TIME_SEPARATOR)
      .appendMinutes()
      .appendLiteral(TIME_SEPARATOR)
      .appendSeconds()
      .toFormatter();
  final AuctionCommModel<?> model;
  Optional<Tree> tree;

  Map<Parcel, TreeItem> parcelItems;

  AuctionPanel(AuctionCommModel<?> m) {
    model = m;
    tree = Optional.absent();

    parcelItems = new LinkedHashMap<>();

    model.getEventAPI().addListener(new Listener() {
      @Override
      public void handleEvent(final Event e) {
        final AuctionEvent ae = (AuctionEvent) e;

        tree.get().getDisplay().asyncExec(new Runnable() {
          @Override
          public void run() {
            if (!parcelItems.containsKey(ae.getParcel())) {
              final TreeItem item = new TreeItem(tree.get(), 0);
              item.setText(ae.getParcel().toString());
              parcelItems.put(ae.getParcel(), item);
            }

            final TreeItem item =
              new TreeItem(parcelItems.get(ae.getParcel()), 0);
            parcelItems.get(ae.getParcel()).setExpanded(true);
            item.setText(new String[] {
                ae.getEventType().toString(),
                FORMATTER.print(new Period(0, ae.getTime())),
                e.getEventType() == EventType.FINISH_AUCTION
                    ? ae.getWinner().get().toString() + " " + ae.getNumBids()
                    : ""
            });
          }
        });

      }
    }, EventType.values());
  }

  @Override
  public void initializePanel(Composite parent) {
    parent.setLayout(new FillLayout());
    tree = Optional.of(
      new Tree(parent, SWT.BORDER | SWT.V_SCROLL | SWT.H_SCROLL));
    tree.get().setHeaderVisible(true);
    final TreeColumn tc = new TreeColumn(tree.get(), 0);
    tc.setText("Parcel");
    tc.setWidth(150);
    final TreeColumn tc4 = new TreeColumn(tree.get(), 0);
    tc4.setText("Time");
    tc4.setWidth(60);

    final TreeColumn tc3 = new TreeColumn(tree.get(), 0);
    tc3.setText("Winner");
    tc3.setWidth(200);
  }

  @Override
  public int preferredSize() {
    return 300;
  }

  @Override
  public int getPreferredPosition() {
    return SWT.LEFT;
  }

  @Override
  public String getName() {
    return "AuctionPanel";
  }

  @Override
  public void render() {

  }

  @Override
  public boolean register(final Parcel element) {
    return true;
  }

  @Override
  public boolean unregister(Parcel element) {
    // TODO Auto-generated method stub
    return false;
  }

  public static Builder builder() {
    return new AutoValue_AuctionPanel_Builder();
  }

  @AutoValue
  public static abstract class Builder
      extends AbstractModelBuilder<AuctionPanel, Parcel> {

    Builder() {
      setDependencies(AuctionCommModel.class);
    }

    @Override
    public AuctionPanel build(DependencyProvider dependencyProvider) {
      return new AuctionPanel(dependencyProvider.get(AuctionCommModel.class));
    }
  }
}
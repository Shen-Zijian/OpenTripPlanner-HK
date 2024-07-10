package org.opentripplanner.model;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.SetMultimap;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Comparator;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import javax.annotation.Nullable;
import org.opentripplanner.routing.algorithm.raptoradapter.transit.mappers.TransitLayerUpdater;
import org.opentripplanner.transit.model.framework.FeedScopedId;
import org.opentripplanner.transit.model.framework.Result;
import org.opentripplanner.transit.model.network.TripPattern;
import org.opentripplanner.transit.model.site.StopLocation;
import org.opentripplanner.transit.model.timetable.TripIdAndServiceDate;
import org.opentripplanner.transit.model.timetable.TripTimes;
import org.opentripplanner.updater.spi.UpdateError;
import org.opentripplanner.updater.spi.UpdateSuccess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A TimetableSnapshot holds a set of realtime-updated Timetables frozen at a moment in time. It
 * can return a Timetable for any TripPattern in the public transit network considering all
 * accumulated realtime updates, falling back on the scheduled Timetable if no updates have been
 * applied for a given TripPattern.
 * <p>
 * This is a central part of managing concurrency when many routing searches may be happening, but
 * realtime updates are also streaming in which change the vehicle arrival and departure times.
 * Any given request will only see one unchanging TimetableSnapshot over the course of its search.
 * <p>
 * An instance of TimetableSnapshot first serves as a buffer to accumulate a batch of incoming
 * updates on top of any already known updates to the base schedules. From time to time such a batch
 * of updates is committed (like a database transaction). At this point the TimetableSnapshot is
 * treated as immutable and becomes available for use by new incoming routing requests.
 * <p>
 * All updates to a snapshot must be completed before it is handed off to any searches. A single
 * snapshot should be used for an entire search, and should remain unchanged for that duration to
 * provide a consistent view not only of trips that have been boarded, but of relative arrival and
 * departure times of other trips that have not necessarily been boarded.
 * <p>
 * A TimetableSnapshot instance may only be modified by a single thread. This makes it easier to
 * reason about how the snapshot is built up and used. Write operations are applied one by one, in
 * order, with no concurrent access. Read operations are then allowed concurrently by many threads
 * after writing is forbidden.
 * <p>
 * The fact that TripPattern instances carry a reference only to their scheduled Timetable and not
 * to their realtime timetable is largely due to historical path-dependence in OTP development.
 * Streaming realtime support was added around 2013 as a sort of sandbox feature that was switched
 * off by default. Looking up realtime timetables during routing was a fringe feature that needed
 * to impose near-zero cost and avoid introducing complexity into the primary codebase. Now over
 * ten years later, the principles of how this system operates are rather stable, but the
 * implementation would benefit from some deduplication and cleanup. Once that is complete, looking
 * up timetables on this class could conceivably be replaced with snapshotting entire views of the
 * transit network. It would also be possible to make the realtime version of Timetables or
 * TripTimes the primary view, and include references back to their scheduled versions.
 */
public class TimetableSnapshot {

  private static final Logger LOG = LoggerFactory.getLogger(TimetableSnapshot.class);

  /**
   * During the construction phase of the TimetableSnapshot, before it is considered immutable and
   * used in routing, this Set holds all timetables that have been modified and are waiting to be
   * indexed. This field will be set to null when the TimetableSnapshot becomes read-only.
   */
  private final Set<Timetable> dirtyTimetables = new HashSet<>();

  /**
   * For each TripPattern (sequence of stops on a particular Route) for which we have received a
   * realtime update, an ordered set of timetables on different days. The key TripPatterns may
   * include ones from the scheduled GTFS, as well as ones added by realtime messages and
   * tracked by the TripPatternCache. <p>
   * Note that the keys do not include all scheduled TripPatterns, only those for which we have at
   * least one update. The type of the field is specifically HashMap (rather than the more general
   * Map interface) because we need to efficiently clone it. <p>
   * The members of the SortedSet (the Timetable for a particular day) are treated as copy-on-write
   * when we're updating them. If an update will modify the timetable for a particular day, that
   * timetable is replicated before any modifications are applied to avoid affecting any previous
   * TimetableSnapshots still in circulation which reference that same Timetable instance. <p>
   * Alternative implementations: A. This could be an array indexed using the integer pattern
   * indexes. B. It could be made into a flat hashtable with compound keys (TripPattern, LocalDate).
   * The compound key approach better reflects the fact that there should be only one Timetable per
   * TripPattern and date.
   */
  private HashMap<TripPattern, SortedSet<Timetable>> timetables = new HashMap();

  /**
   * For cases where the trip pattern (sequence of stops visited) has been changed by a realtime
   * update, a Map associating the updated trip pattern with a compound key of the feed-scoped
   * trip ID and the service date. The type of this field is HashMap rather than the more general
   * Map interface because we need to efficiently clone it whenever we start building up a new
   * snapshot. TODO RT_AB: clarify if this is an index or the original source of truth.
   */
  private HashMap<TripIdAndServiceDate, TripPattern> realtimeAddedTripPattern = new HashMap<>();

  /**
   * This is an index of TripPatterns, not the primary collection. It tracks which TripPatterns
   * that were updated or newly created by realtime messages contain which stops. This allows them
   * to be readily found and included in API responses containing stop times at a specific stop.
   * This is a SetMultimap, so that each pattern is only retained once per stop even if it's added
   * more than once.
   * TODO RT_AB: More general handling of all realtime indexes outside primary data structures.
   */
  private SetMultimap<StopLocation, TripPattern> patternsForStop = HashMultimap.create();

  /**
   * Boolean value indicating that timetable snapshot is read only if true. Once it is true, it
   * shouldn't be possible to change it to false anymore.
   */
  private boolean readOnly = false;

  /**
   * Boolean value indicating that this timetable snapshot contains changes compared to the state of
   * the last commit if true.
   */
  private boolean dirty = false;

  /**
   * Returns an updated timetable for the specified pattern if one is available in this snapshot, or
   * the originally scheduled timetable if there are no updates in this snapshot.
   */
  public Timetable resolve(TripPattern pattern, LocalDate serviceDate) {
    SortedSet<Timetable> sortedTimetables = timetables.get(pattern);

    if (sortedTimetables != null && serviceDate != null) {
      for (Timetable timetable : sortedTimetables) {
        if (timetable != null && timetable.isValidFor(serviceDate)) {
          return timetable;
        }
      }
    }

    return pattern.getScheduledTimetable();
  }

  /**
   * Get the current trip pattern given a trip id and a service date, if it has been changed from
   * the scheduled pattern with an update, for which the stopPattern is different.
   *
   * @return trip pattern created by the updater; null if trip is on the original trip pattern
   */
  @Nullable
  public TripPattern getRealtimeAddedTripPattern(FeedScopedId tripId, LocalDate serviceDate) {
    TripIdAndServiceDate tripIdAndServiceDate = new TripIdAndServiceDate(tripId, serviceDate);
    return realtimeAddedTripPattern.get(tripIdAndServiceDate);
  }

  /**
   * @return if any trip patterns were added.
   */
  public boolean hasRealtimeAddedTripPatterns() {
    return !realtimeAddedTripPattern.isEmpty();
  }

  /**
   * Update the TripTimes of one Trip in a Timetable of a TripPattern. If the Trip of the TripTimes
   * does not exist yet in the Timetable, add it. This method will make a protective copy
   * of the Timetable if such a copy has not already been made while building up this snapshot,
   * handling both cases where patterns were pre-existing in static data or created by realtime data.
   *
   * @param serviceDate service day for which this update is valid
   * @return whether the update was actually applied
   */
  public Result<UpdateSuccess, UpdateError> update(
    TripPattern pattern,
    TripTimes updatedTripTimes,
    LocalDate serviceDate
  ) {
    // Preconditions
    Objects.requireNonNull(pattern);
    Objects.requireNonNull(serviceDate);

    if (readOnly) {
      throw new ConcurrentModificationException("This TimetableSnapshot is read-only.");
    }

    Timetable tt = resolve(pattern, serviceDate);
    // we need to perform the copy of Timetable here rather than in Timetable.update()
    // to avoid repeatedly copying in case several updates are applied to the same timetable
    tt = copyTimetable(pattern, serviceDate, tt);

    // Assume all trips in a pattern are from the same feed, which should be the case.
    // Find trip index
    int tripIndex = tt.getTripIndex(updatedTripTimes.getTrip().getId());
    if (tripIndex == -1) {
      // Trip not found, add it
      tt.addTripTimes(updatedTripTimes);
    } else {
      // Set updated trip times of trip
      tt.setTripTimes(tripIndex, updatedTripTimes);
    }

    if (pattern.isCreatedByRealtimeUpdater()) {
      // Remember this pattern for the added trip id and service date
      FeedScopedId tripId = updatedTripTimes.getTrip().getId();
      TripIdAndServiceDate tripIdAndServiceDate = new TripIdAndServiceDate(tripId, serviceDate);
      realtimeAddedTripPattern.put(tripIdAndServiceDate, pattern);
    }

    // To make these trip patterns visible for departureRow searches.
    addPatternToIndex(pattern);

    // The time tables are finished during the commit

    return Result.success(UpdateSuccess.noWarnings());
  }

  /**
   * This produces a small delay of typically around 50ms, which is almost entirely due to the
   * indexing step. Cloning the map is much faster (2ms). It is perhaps better to index timetables
   * as they are changed to avoid experiencing all this lag at once, but we want to avoid
   * re-indexing when receiving multiple updates for the same timetable in rapid succession. This
   * compromise is expressed by the maxSnapshotFrequency property of StoptimeUpdater. The indexing
   * could be made much more efficient as well.
   *
   * @return an immutable copy of this TimetableSnapshot with all updates applied
   */
  public TimetableSnapshot commit() {
    return commit(null, false);
  }

  @SuppressWarnings("unchecked")
  public TimetableSnapshot commit(TransitLayerUpdater transitLayerUpdater, boolean force) {
    if (readOnly) {
      throw new ConcurrentModificationException("This TimetableSnapshot is read-only.");
    }

    TimetableSnapshot ret = new TimetableSnapshot();
    if (!force && !this.isDirty()) {
      return null;
    }
    ret.timetables = (HashMap<TripPattern, SortedSet<Timetable>>) this.timetables.clone();
    ret.realtimeAddedTripPattern =
      (HashMap<TripIdAndServiceDate, TripPattern>) this.realtimeAddedTripPattern.clone();

    if (transitLayerUpdater != null) {
      transitLayerUpdater.update(dirtyTimetables, timetables);
    }

    this.dirtyTimetables.clear();
    this.dirty = false;

    ret.setPatternsForStop(HashMultimap.create(this.patternsForStop));

    ret.readOnly = true; // mark the snapshot as henceforth immutable
    return ret;
  }

  /**
   * Clear all data of snapshot for the provided feed id
   *
   * @param feedId feed id to clear the snapshot for
   */
  public void clear(String feedId) {
    if (readOnly) {
      throw new ConcurrentModificationException("This TimetableSnapshot is read-only.");
    }
    // Clear all data from snapshot.
    boolean timetableWasModified = clearTimetable(feedId);
    boolean realtimeAddedWasModified = clearRealtimeAddedTripPattern(feedId);

    // If this snapshot was modified, it will be dirty after the clear actions.
    if (timetableWasModified || realtimeAddedWasModified) {
      dirty = true;
    }
  }

  /**
   * If a previous realtime update has changed which trip pattern is associated with the given trip
   * on the given service date, this method will dissociate the trip from that pattern and remove
   * the trip's timetables from that pattern on that particular service date.
   *
   * For this service date, the trip will revert to its original trip pattern from the scheduled
   * data, remaining on that pattern unless it's changed again by a future realtime update.
   *
   * @return true if the trip was found to be shifted to a different trip pattern by a realtime
   * message and an attempt was made to re-associate it with its originally scheduled trip pattern.
   */
  public boolean revertTripToScheduledTripPattern(FeedScopedId tripId, LocalDate serviceDate) {
    boolean success = false;

    final TripPattern pattern = getRealtimeAddedTripPattern(tripId, serviceDate);
    if (pattern != null) {
      // Dissociate the given trip from any realtime-added pattern.
      // The trip will then fall back to its original scheduled pattern.
      realtimeAddedTripPattern.remove(new TripIdAndServiceDate(tripId, serviceDate));
      // Remove times for the trip from any timetables
      // under that now-obsolete realtime-added pattern.
      SortedSet<Timetable> sortedTimetables = this.timetables.get(pattern);
      if (sortedTimetables != null) {
        TripTimes tripTimesToRemove = null;
        for (Timetable timetable : sortedTimetables) {
          if (timetable.isValidFor(serviceDate)) {
            final TripTimes tripTimes = timetable.getTripTimes(tripId);
            if (tripTimes == null) {
              LOG.debug("No triptimes to remove for trip {}", tripId);
            } else if (tripTimesToRemove != null) {
              LOG.debug("Found two triptimes to remove for trip {}", tripId);
            } else {
              tripTimesToRemove = tripTimes;
            }
          }
        }

        if (tripTimesToRemove != null) {
          for (Timetable originalTimetable : sortedTimetables) {
            if (originalTimetable.getTripTimes().contains(tripTimesToRemove)) {
              Timetable updatedTimetable = copyTimetable(pattern, serviceDate, originalTimetable);
              updatedTimetable.getTripTimes().remove(tripTimesToRemove);
            }
          }
        }
      }
      success = true;
    }

    return success;
  }

  /**
   * Removes all Timetables which are valid for a ServiceDate on-or-before the one supplied.
   *
   * @return true if any data has been modified and false if no purging has happened.
   */
  public boolean purgeExpiredData(LocalDate serviceDate) {
    if (readOnly) {
      throw new ConcurrentModificationException("This TimetableSnapshot is read-only.");
    }

    boolean modified = false;
    for (Iterator<TripPattern> it = timetables.keySet().iterator(); it.hasNext();) {
      TripPattern pattern = it.next();
      SortedSet<Timetable> sortedTimetables = timetables.get(pattern);
      SortedSet<Timetable> toKeepTimetables = new TreeSet<>(new SortedTimetableComparator());
      for (Timetable timetable : sortedTimetables) {
        if (serviceDate.compareTo(timetable.getServiceDate()) < 0) {
          toKeepTimetables.add(timetable);
        } else {
          modified = true;
        }
      }

      if (toKeepTimetables.isEmpty()) {
        it.remove();
      } else {
        timetables.put(pattern, ImmutableSortedSet.copyOfSorted(toKeepTimetables));
      }
    }

    // Also remove last added trip pattern for days that are purged
    for (
      Iterator<Entry<TripIdAndServiceDate, TripPattern>> iterator = realtimeAddedTripPattern
        .entrySet()
        .iterator();
      iterator.hasNext();
    ) {
      TripIdAndServiceDate tripIdAndServiceDate = iterator.next().getKey();
      if (serviceDate.compareTo(tripIdAndServiceDate.serviceDate()) >= 0) {
        iterator.remove();
        modified = true;
      }
    }

    return modified;
  }

  public boolean isDirty() {
    if (readOnly) {
      return false;
    }
    return dirty;
  }

  public String toString() {
    String d = readOnly ? "committed" : String.format("%d dirty", dirtyTimetables.size());
    return String.format("Timetable snapshot: %d timetables (%s)", timetables.size(), d);
  }

  public Collection<TripPattern> getPatternsForStop(StopLocation stop) {
    return patternsForStop.get(stop);
  }

  public void setPatternsForStop(SetMultimap<StopLocation, TripPattern> patternsForStop) {
    this.patternsForStop = patternsForStop;
  }

  /**
   * Does this snapshot contain any realtime data or is it completely empty?
   */
  public boolean isEmpty() {
    return dirtyTimetables.isEmpty() && timetables.isEmpty() && realtimeAddedTripPattern.isEmpty();
  }

  /**
   * Clear timetable for all patterns matching the provided feed id.
   *
   * @param feedId feed id to clear out
   * @return true if the timetable changed as a result of the call
   */
  protected boolean clearTimetable(String feedId) {
    return timetables.keySet().removeIf(tripPattern -> feedId.equals(tripPattern.getFeedId()));
  }

  /**
   * Clear all realtime added trip patterns matching the provided feed id.
   *
   * @param feedId feed id to clear out
   * @return true if the realtimeAddedTripPattern changed as a result of the call
   */
  protected boolean clearRealtimeAddedTripPattern(String feedId) {
    return realtimeAddedTripPattern
      .keySet()
      .removeIf(realtimeAddedTripPattern ->
        feedId.equals(realtimeAddedTripPattern.tripId().getFeedId())
      );
  }

  /**
   * Add the patterns to the stop index, only if they come from a modified pattern
   */
  private void addPatternToIndex(TripPattern tripPattern) {
    if (tripPattern.isCreatedByRealtimeUpdater()) {
      //TODO - SIRI: Add pattern to index?

      for (var stop : tripPattern.getStops()) {
        patternsForStop.put(stop, tripPattern);
      }
    }
  }

  /**
   * Make a copy of the given timetable for a given pattern and service date.
   * If the timetable was already copied-on write in this snapshot, the same instance will be
   * returned. The SortedSet that holds the collection of Timetables for that pattern
   * (sorted by service date) is shared between multiple snapshots and must be copied as well.<br/>
   * Note on performance: if  multiple Timetables are modified in a SortedSet, the SortedSet will be
   * copied multiple times. The impact on memory/garbage collection is assumed to be minimal
   * since the collection is small.
   * The SortedSet is made immutable to prevent change after snapshot publication.
   */
  private Timetable copyTimetable(TripPattern pattern, LocalDate serviceDate, Timetable tt) {
    if (!dirtyTimetables.contains(tt)) {
      Timetable old = tt;
      tt = new Timetable(tt, serviceDate);
      SortedSet<Timetable> sortedTimetables = timetables.get(pattern);
      if (sortedTimetables == null) {
        sortedTimetables = new TreeSet<>(new SortedTimetableComparator());
      } else {
        SortedSet<Timetable> temp = new TreeSet<>(new SortedTimetableComparator());
        temp.addAll(sortedTimetables);
        sortedTimetables = temp;
      }
      if (old.getServiceDate() != null) {
        sortedTimetables.remove(old);
      }
      sortedTimetables.add(tt);
      timetables.put(pattern, ImmutableSortedSet.copyOfSorted(sortedTimetables));
      dirtyTimetables.add(tt);
      dirty = true;
    }
    return tt;
  }

  protected static class SortedTimetableComparator implements Comparator<Timetable> {

    @Override
    public int compare(Timetable t1, Timetable t2) {
      return t1.getServiceDate().compareTo(t2.getServiceDate());
    }
  }
}

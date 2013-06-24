/* This file is part of VoltDB.
 * Copyright (C) 2008-2013 VoltDB Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with VoltDB.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.voltcore.agreement;

import static com.google.common.base.Predicates.in;
import static com.google.common.base.Predicates.not;

import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.voltcore.messaging.FailureSiteForwardMessage;
import org.voltcore.messaging.FailureSiteUpdateMessage;
import org.voltcore.utils.Pair;

import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimaps;
import com.google.common.collect.Sets;
import com.google.common.collect.TreeMultimap;

public class AgreementSeeker {

    protected final ArbitrationStrategy m_strategy;

    protected Set<Long> m_hsids;
    protected Set<Long> m_survivors;

    protected final TreeMultimap<Long, Long> m_reported = TreeMultimap.create();
    protected final TreeMultimap<Long, Long> m_witnessed = TreeMultimap.create();
    protected final TreeMultimap<Long, Long> m_alive = TreeMultimap.create();

    public AgreementSeeker(final ArbitrationStrategy strategy) {
        m_strategy = strategy;

        m_hsids = ImmutableSet.of();
        m_survivors = ImmutableSet.of();
    }

    public void startSeekingFor(
            final Set<Long> hsids,
            final Map<Long,Boolean> inTrouble,
            final Set<Long> failedSites) {

        if (!m_hsids.equals(hsids)) {
            if (!m_hsids.isEmpty()) clear(failedSites);
            m_hsids = ImmutableSortedSet.copyOf(hsids);
        }
        m_survivors = m_strategy.accept(survivorPicker, Pair.of(m_hsids, inTrouble));
    }

    public void clear(final Set<Long> failedSites) {
        removeValues(m_reported, failedSites);
        for (Long failed: failedSites) {
            m_reported.removeAll(failed);
        }

        m_witnessed.clear();
        m_alive.clear();

        m_hsids = ImmutableSet.of();
        m_survivors = ImmutableSet.of();
    }

    private void removeValues(TreeMultimap<Long, Long> mm, Set<Long> values) {
        Iterator<Map.Entry<Long, Long>> itr = mm.entries().iterator();
        while (itr.hasNext()) {
            Map.Entry<Long, Long> e = itr.next();
            if (values.contains(e.getValue())) {
                itr.remove();
            }
        }
    }

    static protected final ArbitrationStrategy.Visitor<Set<Long>, Pair<Set<Long>, Map<Long,Boolean>>> survivorPicker =
            new ArbitrationStrategy.Visitor<Set<Long>, Pair<Set<Long>,Map<Long,Boolean>>>() {

        @Override
        public Set<Long> visitMatchingCardinality(Pair<Set<Long>, Map<Long, Boolean>> p) {
            Set<Long> witnessed =
                    Maps.filterEntries(p.getSecond(), amongWitnessedHsids(p.getFirst())).keySet();
            return ImmutableSortedSet.copyOf(Sets.difference(p.getFirst(), witnessed));
        }

        @Override
        public Set<Long> visitNoQuarter(Pair<Set<Long>, Map<Long, Boolean>> p) {
            Set<Long> reported = Maps.filterKeys(p.getSecond(), in(p.getFirst())).keySet();
            return ImmutableSortedSet.copyOf(Sets.difference(p.getFirst(), reported));
        }
    };

    public static Predicate<Map.Entry<Long, Boolean>> amongWitnessedHsids(final Set<Long> hsids) {
        return new Predicate<Map.Entry<Long,Boolean>>() {
            @Override
            public boolean apply(Entry<Long, Boolean> e) {
                return hsids.contains(e.getKey()) && e.getValue();
            }
        };
    }

    public final Predicate<Long> amongSurvivors = new Predicate<Long>() {
        @Override
        public boolean apply(Long site) {
            return m_survivors.contains(site);
        }
    };

    public Set<Long> getSurvivors() {
        return m_survivors;
    }

    private void removeValue(TreeMultimap<Long, Long> mm, long value) {
        Iterator<Map.Entry<Long, Long>> itr = mm.entries().iterator();
        while (itr.hasNext()) {
            Map.Entry<Long,Long> e = itr.next();
            if (e.getValue().equals(value)) {
                itr.remove();
            }
        }
    }

    public boolean isUnwitnessedStale(long failedHsid, long self) {
        return m_reported.get(failedHsid).contains(self);
    }

    public void add(long reportingHsid, final Map<Long,Boolean> failed) {
        if (!m_hsids.contains(reportingHsid)) return;

        Boolean harakiri = failed.get(reportingHsid);
        if (harakiri != null && harakiri.booleanValue()) return;

        Set<Long> witnessed = Sets.newHashSet();

        for (Map.Entry<Long, Boolean> e: failed.entrySet()) {
            if (!m_hsids.contains(e.getKey())) continue;
            m_reported.put(e.getKey(), reportingHsid);
            if (e.getValue()) {
                m_witnessed.put(e.getKey(), reportingHsid);
                witnessed.add(e.getKey());
            }
        }

        removeValue(m_alive, reportingHsid);

        for (Long alive: Sets.difference(m_hsids, witnessed)) {
            m_alive.put(alive, reportingHsid);
        }
    }

    protected void add(FailureSiteUpdateMessage fsum, Long reportingHsid) {
        add(reportingHsid,fsum.m_failedHSIds);
    }

    public void add(FailureSiteUpdateMessage fsum) {
        add(fsum, fsum.m_sourceHSId);
    }

    public void add(FailureSiteForwardMessage fsfm) {
        add(fsfm, fsfm.m_reportingHSId);
    }

    protected Boolean haveAgreement(Scenario sc) {
        return m_strategy.accept(agreementSeeker, sc);
    }

    protected final ArbitrationStrategy.Visitor<Boolean, Scenario> agreementSeeker =
            new ArbitrationStrategy.Visitor<Boolean, Scenario>() {

        @Override
        public Boolean visitNoQuarter(Scenario sc) {
            Iterator<Long> itr = sc.reported.keySet().iterator();
            boolean agree = true;
            while (agree && itr.hasNext()) {
                agree = sc.reported.get(itr.next()).containsAll(sc.survivors);
            }
            return agree;
        }

        // it matches if witnessed sets are the same ad contain all alives
        @Override
        public Boolean visitMatchingCardinality(Scenario sc) {
            boolean agree = true;
            Set<Long> quorum = Sets.intersection(sc.alive.keySet(),sc.survivors);
            for (Long w: sc.witnessed.keySet()) {
                agree = agree && quorum.equals(sc.witnessed.get(w));
            }
            return agree;
        }
    };

    public boolean needForward(long hsid) {
        return m_strategy.accept(forwardDemander, hsid);
    }

    protected ArbitrationStrategy.Visitor<Boolean, Long> forwardDemander =
            new ArbitrationStrategy.Visitor<Boolean, Long>() {

        @Override
        public Boolean visitNoQuarter(Long hsid) {
            return false;
        }

        @Override
        public Boolean visitMatchingCardinality(Long hsid) {
            Set<Long> unreachable = Sets.filter(m_hsids, not(amongSurvivors));
            Set<Long> butAlive = Sets.intersection(m_alive.keySet(), unreachable);

            return !butAlive.isEmpty()
                && seenByInterconnectedPeers(butAlive, Sets.newTreeSet(m_survivors))
                && !m_witnessed.get(hsid).containsAll(butAlive);
        }
    };

    protected boolean seenByInterconnectedPeers( Set<Long> who, Set<Long> byWhom) {
        Set<Long> seers = Multimaps.filterValues(m_alive, in(byWhom)).keySet();
        int before = byWhom.size();

        byWhom.addAll(seers);
        if (byWhom.containsAll(who)) {
            return true;
        } else if (byWhom.size() == before) {
            return false;
        }
        return seenByInterconnectedPeers(who, byWhom);
    }

    public Set<Long> nextKill(long selfHsid) {
        return m_strategy.accept(killPicker, selfHsid);
    }

    protected ArbitrationStrategy.Visitor<Set<Long>, Long> killPicker =
            new ArbitrationStrategy.Visitor<Set<Long>, Long>() {

                @Override
                public Set<Long> visitNoQuarter(Long self) {
                    return ImmutableSet.copyOf(m_reported.keySet());
                }

                // When recomputing you need to remove all alive by (a)
                // and add them to witnessed
                // alive(a) is c,d,e,f now witnessed(a) += alive(a)
                // you also have to remove all (a)'s from witnessedBy values,
                // as (a) can no longer witness
                @Override
                public Set<Long> visitMatchingCardinality(Long self) {
                    Set<Long> picks = Sets.newHashSet();
                    Scenario sc = new Scenario();
                    while (!haveAgreement(sc)) {
                        Long pick = null;
                        for (Long s: sc.witnessed.keySet()) {

                            if (s.equals(self) || picks.contains(s)) continue;

                            Set<Long> witnessedBy = sc.witnessed.get(s);
                            if (witnessedBy.isEmpty()) continue;

                            if (pick != null) {
                                int cmp = witnessedBy.size()
                                        - sc.witnessed.get(pick).size();
                                if (cmp > 0 || (cmp == 0 && s.compareTo(pick) > 0)) {
                                    pick = s;
                                }
                            } else {
                                pick = s;
                            }
                        }
                        if (pick == null) {
                            return ImmutableSet.of();
                        }

                        removeValue(sc.witnessed,pick);
                        removeValue(sc.alive, pick);

                        sc.witnessed.putAll(pick, sc.alive.removeAll(pick));

                        picks.add(pick);
                    }
                    return ImmutableSet.copyOf(sc.witnessed.keySet());
                }
            };

    public Set<Long> forWhomSiteIsDead(long hsid) {
        ImmutableSet.Builder<Long> isb = ImmutableSet.builder();
        Set<Long> witnessedBy = m_witnessed.get(hsid);
        if (   !witnessedBy.isEmpty()
            && m_survivors.contains(hsid)
            && m_strategy == ArbitrationStrategy.MATCHING_CARDINALITY) {
            isb.addAll(Sets.filter(witnessedBy, amongSurvivors));
        }
        return isb.build();
    }

    protected class Scenario {

        protected TreeMultimap<Long, Long> reported;
        protected TreeMultimap<Long, Long> witnessed;
        protected TreeMultimap<Long, Long> alive;
        protected Set<Long> survivors;

        protected Scenario() {
            reported = TreeMultimap.create(m_reported);
            witnessed = TreeMultimap.create(m_witnessed);
            survivors = Sets.newTreeSet(m_survivors);
            alive = TreeMultimap.create(m_alive);
        }
    }
}
/**

The Notice below must appear in each file of the Source Code of any
copy you distribute of the Licensed Product.  Contributors to any
Modifications may add their own copyright notices to identify their
own contributions.

License:

The contents of this file are subject to the CognitiveWeb Open Source
License Version 1.1 (the License).  You may not copy or use this file,
in either source code or executable form, except in compliance with
the License.  You may obtain a copy of the License from

  http://www.CognitiveWeb.org/legal/license/

Software distributed under the License is distributed on an AS IS
basis, WITHOUT WARRANTY OF ANY KIND, either express or implied.  See
the License for the specific language governing rights and limitations
under the License.

Copyrights:

Portions created by or assigned to CognitiveWeb are Copyright
(c) 2003-2003 CognitiveWeb.  All Rights Reserved.  Contact
information for CognitiveWeb is available at

  http://www.CognitiveWeb.org

Portions Copyright (c) 2002-2003 Bryan Thompson.

Acknowledgements:

Special thanks to the developers of the Jabber Open Source License 1.0
(JOSL), from which this License was derived.  This License contains
terms that differ from JOSL.

Special thanks to the CognitiveWeb Open Source Contributors for their
suggestions and support of the Cognitive Web.

Modifications:

*/
/*
 * Created on Feb 7, 2007
 */

package com.bigdata.scaleup;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;

import com.bigdata.journal.ICommitter;
import com.bigdata.journal.Journal;
import com.bigdata.objndx.AbstractBTree;
import com.bigdata.objndx.BTree;
import com.bigdata.objndx.BatchContains;
import com.bigdata.objndx.BatchInsert;
import com.bigdata.objndx.BatchLookup;
import com.bigdata.objndx.BatchRemove;
import com.bigdata.objndx.EmptyEntryIterator;
import com.bigdata.objndx.FusedView;
import com.bigdata.objndx.IEntryIterator;
import com.bigdata.objndx.IIndex;
import com.bigdata.objndx.IndexSegment;

/**
 * A mutable B+-Tree that is dynamically partitioned into one or more key
 * ranges. Each key range is a <i>partition</i>. A partition is defined by the
 * smallest key that can enter that partition. A {@link MetadataIndex} contains
 * the definitions of the partitions. Each partition has a mutable {@link BTree}
 * that will absorb writes for a partition and the same {@link BTree} may be
 * used to absorb writes for multiple partitions of the same index. Periodically
 * the writes on the {@link BTree} are evicted into one or more
 * {@link IndexSegment}s that contain historical read-only data for that
 * partition.
 * <p>
 * The scale up design uses a single {@link Journal journal} to absorb writes
 * for all partitions of all indices and locates all {@link IndexSegment}s on
 * the same host.
 * <p>
 * Writes on a {@link PartitionIndex} simply write through to the {@link BTree}
 * corresponding to that index (having the same name) on the
 * {@link Journal journal} (they are effectively multiplexed on the journal).
 * <p>
 * Read operations process the spanned partitions sequence so that the
 * aggregated results are in index order. For each partition, the read request
 * is constructed using a {@link FusedView} of the mutable {@link BTree} used to
 * absorb writes and the live {@link IndexSegment}s for that partition.
 * 
 * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
 * @version $Id$
 */
public class PartitionedIndex implements IIndex, ICommitter {

    /**
     * The mutable {@link BTree} used to absorb all writes for the
     * {@link PartitionedIndex}.
     */
    protected final BTree btree;

    /**
     * The metadata index used to locate the partitions relevant to a given read
     * operation.
     */
    private final MetadataIndex mdi;

    /**
     * A cache of the fused views for in use partitions.
     * 
     * @todo reconcile this with
     *       {@link PartitionedJournal#getIndex(String, IResourceMetadata)}
     *       which provides a weak value cache for index segments.
     */
    private final Map<Integer,FusedView> views = new HashMap<Integer,FusedView>();

    public PartitionedJournal getMaster() {
        
        return getSlave().getMaster();
        
    }
    
    public SlaveJournal getSlave() {
        
        return (SlaveJournal)getBTree().getStore();
        
    }
    
    /**
     * The mutable {@link BTree} used to absorb writes.
     */
    public BTree getBTree() {
        
        return btree;
        
    }
    
    /**
     * Opens and returns the live {@link IndexSegment}s for the partition.
     * These {@link IndexSegments} MUST be explicitly closed in order to reclaim
     * resources and release the lock on the backing files.
     * 
     * @param pmd
     *            The partition.
     * 
     * @return The live {@link IndexSegment}s for that partition.
     */
    protected IndexSegment[] openIndexSegments(PartitionMetadata pmd) {
        
        final int liveCount = pmd.getLiveCount();
        
        IndexSegment[] segs = new IndexSegment[liveCount];
        
        PartitionedJournal master = getMaster();
        
        int n = 0;
        
        for(int i=0; i<pmd.segs.length; i++) {
            
            if(pmd.segs[i].state != ResourceState.Live) continue;
            
            segs[n++] = (IndexSegment) master.getIndex(getName(), pmd.segs[i]);
            
        }
        
        assert n == liveCount;
        
        return segs;

    }

    /**
     * Close all open views, including any backing index segments.
     */
    protected void closeViews() {

        Iterator<Map.Entry<Integer,FusedView>> itr = views.entrySet().iterator();
        
        while(itr.hasNext()) {
            
            FusedView view = itr.next().getValue();
            
            // @todo assumes one open segment per view.
            IndexSegment seg = (IndexSegment)view.srcs[1];
            
            seg.close();
            
        }

        views.clear();
        
    }
    
    /**
     * Return a fused view on the partition in which the key would be found. The
     * view is cached and will be reused on subsequent requests. This operation
     * can have high latency if the view for the partition is not in the cache.
     * 
     * @param key
     *            The search key.
     * 
     * @return The fused view.
     * 
     * @todo use an weak value LRU policy to close out partitions that are not
     *       being actively used. note that we still need to know which
     *       partitions have been touched on a {@link PartitionedIndex} when we
     *       export its data in response to a journal overflow event. This
     *       suggests a hard reference queue to hold onto recently used views
     *       and a persistent bitmap or flag in the metadata index to identify
     *       those partitions for which we need to export data. Note that we
     *       MUST hold onto any view that is currently being used to report data
     *       to the application, e.g., by a striterator. This means that we need
     *       to use a hash map with weak or soft reference values.
     *       <p>
     *       Alternatively, perhaps we can write a custom iterator over the
     *       mutable btree for the index that automatically exports or performs
     *       a compacting merge for each partition for which it encounters data
     *       on an entry scan.
     */
    protected IIndex getView(byte[] key) {
        
        PartitionMetadata pmd = mdi.find(key);
        
        FusedView view = views.get(pmd.partId);
        
        if(view==null) {
            
            if(pmd.getLiveCount()==0) {
                
                // the btree is the view.
                return btree;
                
            }
            
            /*
             * Open the live index segments for this partition (high latency).
             */
            
            IndexSegment[] segs = openIndexSegments(pmd);
            
            AbstractBTree[] sources = new AbstractBTree[segs.length+1];
            
            // the mutable btree.
            sources[0] = btree;
            
            // the immutable historical segments.
            System.arraycopy(segs, 0, sources, 1, segs.length);
            
            // create the fused view.
            view = new FusedView(sources);
            
            // place the view in the cache.
            views.put(pmd.partId, view);
            
        }
        
        return view;
        
    }

    /**
     * Return the resources required to provide a coherent view of the partition
     * in which the key is found.
     * 
     * @param key
     *            The key.
     * 
     * @return The resources required to read on the partition containing that
     *         key. The resources are arranged in reverse timestamp order
     *         (increasing age).
     * 
     * @todo reconcile with {@link #getView(byte[])}?
     */
    protected IResourceMetadata[] getResources(byte[] key) {

        JournalMetadata journalResource = new JournalMetadata((Journal) btree
                .getStore(), ResourceState.Live);

        PartitionMetadata pmd = mdi.find(key);

        final int liveCount = pmd.getLiveCount();

        IResourceMetadata[] resources = new IResourceMetadata[liveCount + 1];

        int n = 0;

        resources[n++] = journalResource;

        for (int i = 0; i < resources.length; i++) {

            SegmentMetadata seg = pmd.segs[i];

            if (seg.state != ResourceState.Live)
                continue;

            resources[n++] = seg;

        }

        assert n == resources.length;
        
        return resources;

    }
    
    public String getName() {
        
        return mdi.getName();
        
    }
    
    /**
     * @param btree
     *            The mutable {@link BTree} used to absorb all writes for the
     *            {@link PartitionedIndex}.
     * @param mdi
     *            The metadata index used to locate the partitions relevant to a
     *            given read operation.
     */
    public PartitionedIndex(BTree btree, MetadataIndex mdi) {
        
        this.btree = btree;
        
        this.mdi = mdi;
        
    }
    
    /*
     * Non-batch API.
     * 
     * The write operations are trivial since we always direct them to the named
     * btree on the journal.
     * 
     * The point read operations are also trivial conceptually. The are always
     * directed to the fused view of the mutable btree on the journal and the
     * live index segments for the partition that spans the search key.
     * 
     * The rangeCount and rangeIterator operations are more complex. The need to
     * be processed by each partition spanned by the from/to key range. We need
     * to present the operation to each partition in turn and append the results
     * together.
     */

    public Object insert(Object key, Object value) {

        return btree.insert(key, value);
        
    }

    public Object remove(Object key) {

        return btree.remove(key);
        
    }

    public boolean contains(byte[] key) {

        return getView(key).contains(key);
        
    }

    public Object lookup(Object key) {

        return getView((byte[])key).lookup(key);
        
    }

    /**
     * For each partition spanned by the key range, report the sum of the counts
     * for each source in the view for that partition (since this is based on 
     * {@link FusedView}s for each partition, it will may overcount the #of
     * entries actually in the range on each partition).
     * <p>
     * If the count would exceed {@link Integer#MAX_VALUE} then the result is
     * {@link Integer#MAX_VALUE}.
     */
    public int rangeCount(byte[] fromKey, byte[] toKey) {

        // index of the first partition to check.
        final int fromIndex = (fromKey == null ? 0 : mdi.findIndexOf(fromKey));

        // index of the last partition to check.
        final int toIndex = (toKey == null ? 0 : mdi.findIndexOf(toKey));

        // per javadoc, keys out of order returns zero(0).
        if(toIndex<fromIndex) return 0;

        // use to counters so that we can look for overflow.
        int count = 0;
        int lastCount = 0;
        
        for( int index = fromIndex; index<=toIndex; index++) {

            // The first key that would enter the nth partition.
            byte[] separatorKey = mdi.keyAt(index);

            // Add in the count from that partition.
            count += getView(separatorKey).rangeCount(fromKey, toKey);
            
            if(count<lastCount) {
                
                return Integer.MAX_VALUE;
                
            }
            
            lastCount = count;
            
        }
        
        return count;
        
    }

    /**
     * Return an iterator that visits all values in the key range in key order.
     * The iterator will visit each partition spanned by the key range in turn.
     */
    public IEntryIterator rangeIterator(byte[] fromKey, byte[] toKey) {

        // index of the first partition to check.
        final int fromIndex = (fromKey == null ? 0 : mdi.findIndexOf(fromKey));

        // index of the last partition to check.
        final int toIndex = (toKey == null ? 0 : mdi.findIndexOf(toKey));

        // keys are out of order.
        if(toIndex<fromIndex) return EmptyEntryIterator.INSTANCE;

        // iterator that will visit all key/vals in that range.
        return new PartitionedRangeIterator(fromKey, toKey, fromIndex, toIndex);

    }
    
    /*
     * Batch API.
     * 
     * The write operations are trivial since we always direct them to the named
     * btree on the journal.
     * 
     * The read operations are more complex. We need to partition the set of
     * keys based on the partitions to which those keys would be directed. This
     * is basically a join against the {@link MetadataIndex}. This operation is
     * also required on the rangeCount and rangeIterator methods on the
     * non-batch api.
     */

    public void insert(BatchInsert op) {

        btree.insert(op);

    }

    public void remove(BatchRemove op) {

        btree.remove(op);

    }

    // FIXME contains(batch)
    public void contains(BatchContains op) {

        throw new UnsupportedOperationException();

    }

    // FIXME lookup(batch)
    public void lookup(BatchLookup op) {

        throw new UnsupportedOperationException();

    }

    /**
     * An iterator that visits all key/value entries in a specified key range
     * across one or more partitions.
     * 
     * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
     * @version $Id$
     */
    private class PartitionedRangeIterator implements IEntryIterator {

        /**
         * The first key to visit or null iff there is no lower bound.
         */
        private final byte[] fromKey;

        /**
         * The first key NOT to visit or null iff there is no upper bound.
         */
        private final byte[] toKey;

        /**
         * The index of the first partition spanned by the key range.
         */
        private final int fromIndex;

        /**
         * The index of the last partition spanned by the key range.
         */
        private final int toIndex;

        /**
         * The index of the partition whose entries are currently being visited.
         */
        private int index;
        
        /**
         * The iterator for the current partition.
         */
        private IEntryIterator src;

        /**
         * The last visited key and null iff we have not visited anything yet.
         */
        private byte[] key = null;

        /**
         * The last visited value.
         */
        private Object val = null;

        /**
         * 
         * @param fromKey
         *            The first key to visit or null iff there is no lower
         *            bound.
         * @param toKey
         *            The first key NOT to visit or null iff there is no upper
         *            bound.
         * @param fromIndex
         *            The index of the first partition spanned by the key range.
         * @param toIndex
         *            The index of the last partition spanned by the key range.
         */
        public PartitionedRangeIterator(byte[] fromKey, byte[] toKey, int fromIndex,int toIndex) {

            assert fromIndex >= 0;
            assert toIndex >= fromIndex;
            
            this.fromKey = fromKey;
            
            this.toKey = toKey;
            
            this.fromIndex = fromIndex;
            
            this.toIndex = toIndex;

            // the first partition to visit.
            this.index = fromIndex;
            
            // The first key that would enter that partition.
            byte[] separatorKey = mdi.keyAt(index);

            // The rangeIterator for that partition.
            src = getView(separatorKey).rangeIterator(fromKey, toKey);
            
        }

        public boolean hasNext() {

            if(src.hasNext()) return true;

            // The current partition has been exhausted.
            if(index < toIndex) {
                
                // the next partition to visit.
                index++;
                
                // The first key that would enter that partition.
                byte[] separatorKey = mdi.keyAt(index);

                // The rangeIterator for that partition.
                src = getView(separatorKey).rangeIterator(fromKey, toKey);

                return src.hasNext();

            } else {

                // All partitions have been exhausted.
                return false;
            
            }
            
        }

        public Object next() {

            if (!hasNext()) {

                throw new NoSuchElementException();

            }

            /*
             * eagerly fetch the key and value so that we can report them using
             * getKey() and getValue()
             */
            val = src.next();
            key = src.getKey();

            return val;

        }

        public Object getValue() {

            if (key == null) {
                
                // nothing has been visited yet.
                throw new IllegalStateException();
                
            }

            return val;

        }

        public byte[] getKey() {

            if (key == null) {
                
                // nothing has been visited yet.
                throw new IllegalStateException();
                
            }
            
            return key;

        }

        /**
         * Not supported.
         * 
         * @exception UnsupportedOperationException
         *                Always.
         */
        public void remove() {

            throw new UnsupportedOperationException();

        }

    }

    public long handleCommit() {

        return btree.handleCommit();
        
    }

}

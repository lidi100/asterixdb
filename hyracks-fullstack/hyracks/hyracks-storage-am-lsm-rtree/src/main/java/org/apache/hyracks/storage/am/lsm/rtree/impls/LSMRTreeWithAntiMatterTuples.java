/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.hyracks.storage.am.lsm.rtree.impls;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.hyracks.api.dataflow.value.IBinaryComparatorFactory;
import org.apache.hyracks.api.dataflow.value.ILinearizeComparatorFactory;
import org.apache.hyracks.api.exceptions.HyracksDataException;
import org.apache.hyracks.api.io.IIOManager;
import org.apache.hyracks.dataflow.common.data.accessors.ITupleReference;
import org.apache.hyracks.storage.am.btree.impls.BTreeRangeSearchCursor;
import org.apache.hyracks.storage.am.btree.impls.RangePredicate;
import org.apache.hyracks.storage.am.common.api.ITreeIndexAccessor;
import org.apache.hyracks.storage.am.common.api.ITreeIndexCursor;
import org.apache.hyracks.storage.am.common.api.ITreeIndexFrameFactory;
import org.apache.hyracks.storage.am.common.impls.NoOpOperationCallback;
import org.apache.hyracks.storage.am.common.tuples.PermutingTupleReference;
import org.apache.hyracks.storage.am.lsm.common.api.ILSMComponent;
import org.apache.hyracks.storage.am.lsm.common.api.ILSMComponentFilterFactory;
import org.apache.hyracks.storage.am.lsm.common.api.ILSMComponentFilterFrameFactory;
import org.apache.hyracks.storage.am.lsm.common.api.ILSMDiskComponent;
import org.apache.hyracks.storage.am.lsm.common.api.ILSMDiskComponentFactory;
import org.apache.hyracks.storage.am.lsm.common.api.ILSMIOOperation;
import org.apache.hyracks.storage.am.lsm.common.api.ILSMIOOperationCallback;
import org.apache.hyracks.storage.am.lsm.common.api.ILSMIOOperationScheduler;
import org.apache.hyracks.storage.am.lsm.common.api.ILSMIndexAccessor;
import org.apache.hyracks.storage.am.lsm.common.api.ILSMIndexFileManager;
import org.apache.hyracks.storage.am.lsm.common.api.ILSMIndexOperationContext;
import org.apache.hyracks.storage.am.lsm.common.api.ILSMMemoryComponent;
import org.apache.hyracks.storage.am.lsm.common.api.ILSMMergePolicy;
import org.apache.hyracks.storage.am.lsm.common.api.ILSMOperationTracker;
import org.apache.hyracks.storage.am.lsm.common.api.IVirtualBufferCache;
import org.apache.hyracks.storage.am.lsm.common.api.LSMOperationType;
import org.apache.hyracks.storage.am.lsm.common.impls.AbstractLSMIndexOperationContext;
import org.apache.hyracks.storage.am.lsm.common.impls.LSMComponentFileReferences;
import org.apache.hyracks.storage.am.lsm.common.impls.LSMComponentFilterManager;
import org.apache.hyracks.storage.am.lsm.common.impls.LSMIndexSearchCursor;
import org.apache.hyracks.storage.am.lsm.common.impls.LSMTreeIndexAccessor;
import org.apache.hyracks.storage.am.lsm.common.impls.LSMTreeIndexAccessor.ICursorFactory;
import org.apache.hyracks.storage.am.lsm.common.impls.MergeOperation;
import org.apache.hyracks.storage.am.lsm.common.impls.TreeIndexFactory;
import org.apache.hyracks.storage.am.rtree.impls.RTree;
import org.apache.hyracks.storage.am.rtree.impls.RTreeSearchCursor;
import org.apache.hyracks.storage.am.rtree.impls.SearchPredicate;
import org.apache.hyracks.storage.common.IIndexBulkLoader;
import org.apache.hyracks.storage.common.IIndexCursor;
import org.apache.hyracks.storage.common.IModificationOperationCallback;
import org.apache.hyracks.storage.common.ISearchOperationCallback;
import org.apache.hyracks.storage.common.ISearchPredicate;
import org.apache.hyracks.storage.common.MultiComparator;
import org.apache.hyracks.storage.common.file.IFileMapProvider;

public class LSMRTreeWithAntiMatterTuples extends AbstractLSMRTree {
    private static final ICursorFactory cursorFactory = opCtx -> new LSMRTreeWithAntiMatterTuplesSearchCursor(opCtx);
    // On-disk components.
    // For creating RTree's used in bulk load. Different from diskRTreeFactory
    // because it should have a different tuple writer in it's leaf frames.
    private final ILSMDiskComponentFactory bulkLoaComponentFactory;

    public LSMRTreeWithAntiMatterTuples(IIOManager ioManager, List<IVirtualBufferCache> virtualBufferCaches,
            ITreeIndexFrameFactory rtreeInteriorFrameFactory, ITreeIndexFrameFactory rtreeLeafFrameFactory,
            ITreeIndexFrameFactory btreeInteriorFrameFactory, ITreeIndexFrameFactory btreeLeafFrameFactory,
            ILSMIndexFileManager fileManager, TreeIndexFactory<RTree> diskRTreeFactory,
            TreeIndexFactory<RTree> bulkLoadRTreeFactory, ILSMComponentFilterFactory filterFactory,
            ILSMComponentFilterFrameFactory filterFrameFactory, LSMComponentFilterManager filterManager,
            IFileMapProvider diskFileMapProvider, int fieldCount, IBinaryComparatorFactory[] rtreeCmpFactories,
            IBinaryComparatorFactory[] btreeComparatorFactories, ILinearizeComparatorFactory linearizer,
            int[] comparatorFields, IBinaryComparatorFactory[] linearizerArray, ILSMMergePolicy mergePolicy,
            ILSMOperationTracker opTracker, ILSMIOOperationScheduler ioScheduler, ILSMIOOperationCallback ioOpCallback,
            int[] rtreeFields, int[] filterFields, boolean durable, boolean isPointMBR) throws HyracksDataException {
        super(ioManager, virtualBufferCaches, rtreeInteriorFrameFactory, rtreeLeafFrameFactory,
                btreeInteriorFrameFactory, btreeLeafFrameFactory, fileManager,
                new LSMRTreeWithAntiMatterTuplesDiskComponentFactory(diskRTreeFactory, filterFactory),
                diskFileMapProvider, fieldCount, rtreeCmpFactories, btreeComparatorFactories, linearizer,
                comparatorFields, linearizerArray, 0, mergePolicy, opTracker, ioScheduler, ioOpCallback, filterFactory,
                filterFrameFactory, filterManager, rtreeFields, filterFields, durable, isPointMBR,
                diskRTreeFactory.getBufferCache());
        bulkLoaComponentFactory =
                new LSMRTreeWithAntiMatterTuplesDiskComponentFactory(bulkLoadRTreeFactory, filterFactory);
    }

    @Override
    protected ILSMDiskComponent loadComponent(LSMComponentFileReferences refs) throws HyracksDataException {
        return createDiskComponent(componentFactory, refs.getInsertIndexFileReference(), null, null, false);
    }

    @Override
    protected void deactivateDiskComponent(ILSMDiskComponent c) throws HyracksDataException {
        RTree rtree = ((LSMRTreeDiskComponent) c).getRTree();
        rtree.deactivateCloseHandle();
    }

    @Override
    protected void destroyDiskComponent(ILSMDiskComponent c) throws HyracksDataException {
        ((LSMRTreeDiskComponent) c).getRTree().destroy();
    }

    @Override
    protected void clearDiskComponent(ILSMDiskComponent c) throws HyracksDataException {
        RTree rtree = ((LSMRTreeDiskComponent) c).getRTree();
        rtree.deactivate();
        rtree.destroy();
    }

    @Override
    public ILSMDiskComponent flush(ILSMIOOperation operation) throws HyracksDataException {
        LSMRTreeFlushOperation flushOp = (LSMRTreeFlushOperation) operation;
        // Renaming order is critical because we use assume ordering when we
        // read the file names when we open the tree.
        // The RTree should be renamed before the BTree.
        LSMRTreeMemoryComponent flushingComponent = (LSMRTreeMemoryComponent) flushOp.getFlushingComponent();
        ITreeIndexAccessor memRTreeAccessor = flushingComponent.getRTree()
                .createAccessor(NoOpOperationCallback.INSTANCE, NoOpOperationCallback.INSTANCE);
        RTreeSearchCursor rtreeScanCursor = (RTreeSearchCursor) memRTreeAccessor.createSearchCursor(false);
        SearchPredicate rtreeNullPredicate = new SearchPredicate(null, null);
        memRTreeAccessor.search(rtreeScanCursor, rtreeNullPredicate);
        LSMRTreeDiskComponent component = createDiskComponent(componentFactory, flushOp.getTarget(), null, null, true);
        RTree diskRTree = component.getRTree();

        // scan the memory BTree
        ITreeIndexAccessor memBTreeAccessor = flushingComponent.getBTree()
                .createAccessor(NoOpOperationCallback.INSTANCE, NoOpOperationCallback.INSTANCE);
        BTreeRangeSearchCursor btreeScanCursor = (BTreeRangeSearchCursor) memBTreeAccessor.createSearchCursor(false);
        RangePredicate btreeNullPredicate = new RangePredicate(null, null, true, true, null, null);
        memBTreeAccessor.search(btreeScanCursor, btreeNullPredicate);

        // Since the LSM-RTree is used as a secondary assumption, the
        // primary key will be the last comparator in the BTree comparators
        TreeTupleSorter rTreeTupleSorter = new TreeTupleSorter(flushingComponent.getRTree().getFileId(),
                linearizerArray, rtreeLeafFrameFactory.createFrame(), rtreeLeafFrameFactory.createFrame(),
                flushingComponent.getRTree().getBufferCache(), comparatorFields);

        TreeTupleSorter bTreeTupleSorter = new TreeTupleSorter(flushingComponent.getBTree().getFileId(),
                linearizerArray, btreeLeafFrameFactory.createFrame(), btreeLeafFrameFactory.createFrame(),
                flushingComponent.getBTree().getBufferCache(), comparatorFields);
        // BulkLoad the tuples from the in-memory tree into the new disk
        // RTree.

        boolean isEmpty = true;
        try {
            while (rtreeScanCursor.hasNext()) {
                isEmpty = false;
                rtreeScanCursor.next();
                rTreeTupleSorter.insertTupleEntry(rtreeScanCursor.getPageId(), rtreeScanCursor.getTupleOffset());
            }
        } finally {
            rtreeScanCursor.close();
        }
        if (!isEmpty) {
            rTreeTupleSorter.sort();
        }

        isEmpty = true;
        try {
            while (btreeScanCursor.hasNext()) {
                isEmpty = false;
                btreeScanCursor.next();
                bTreeTupleSorter.insertTupleEntry(btreeScanCursor.getPageId(), btreeScanCursor.getTupleOffset());
            }
        } finally {
            btreeScanCursor.close();
        }
        if (!isEmpty) {
            bTreeTupleSorter.sort();
        }

        IIndexBulkLoader rTreeBulkloader = diskRTree.createBulkLoader(1.0f, false, 0L, false);
        LSMRTreeWithAntiMatterTuplesFlushCursor cursor = new LSMRTreeWithAntiMatterTuplesFlushCursor(rTreeTupleSorter,
                bTreeTupleSorter, comparatorFields, linearizerArray);
        cursor.open(null, null);

        try {
            while (cursor.hasNext()) {
                cursor.next();
                ITupleReference frameTuple = cursor.getTuple();

                rTreeBulkloader.add(frameTuple);
            }
        } finally {
            cursor.close();
        }

        if (component.getLSMComponentFilter() != null) {
            List<ITupleReference> filterTuples = new ArrayList<>();
            filterTuples.add(flushingComponent.getLSMComponentFilter().getMinTuple());
            filterTuples.add(flushingComponent.getLSMComponentFilter().getMaxTuple());
            getFilterManager().updateFilter(component.getLSMComponentFilter(), filterTuples);
            getFilterManager().writeFilter(component.getLSMComponentFilter(), component.getRTree());
        }
        flushingComponent.getMetadata().copy(component.getMetadata());
        rTreeBulkloader.end();

        return component;
    }

    @Override
    public ILSMDiskComponent merge(ILSMIOOperation operation) throws HyracksDataException {
        MergeOperation mergeOp = (MergeOperation) operation;
        IIndexCursor cursor = mergeOp.getCursor();
        ISearchPredicate rtreeSearchPred = new SearchPredicate(null, null);
        ILSMIndexOperationContext opCtx = ((LSMIndexSearchCursor) cursor).getOpCtx();
        opCtx.getComponentHolder().addAll(mergeOp.getMergingComponents());
        search(opCtx, cursor, rtreeSearchPred);

        // Bulk load the tuples from all on-disk RTrees into the new RTree.
        LSMRTreeDiskComponent component = createDiskComponent(componentFactory, mergeOp.getTarget(), null, null, true);
        RTree mergedRTree = component.getRTree();
        IIndexBulkLoader bulkloader = mergedRTree.createBulkLoader(1.0f, false, 0L, false);
        try {
            while (cursor.hasNext()) {
                cursor.next();
                ITupleReference frameTuple = cursor.getTuple();
                bulkloader.add(frameTuple);
            }
        } finally {
            cursor.close();
        }
        if (component.getLSMComponentFilter() != null) {
            List<ITupleReference> filterTuples = new ArrayList<>();
            for (int i = 0; i < mergeOp.getMergingComponents().size(); ++i) {
                filterTuples.add(mergeOp.getMergingComponents().get(i).getLSMComponentFilter().getMinTuple());
                filterTuples.add(mergeOp.getMergingComponents().get(i).getLSMComponentFilter().getMaxTuple());
            }
            getFilterManager().updateFilter(component.getLSMComponentFilter(), filterTuples);
            getFilterManager().writeFilter(component.getLSMComponentFilter(), component.getRTree());
        }
        bulkloader.end();

        return component;
    }

    @Override
    public ILSMIndexAccessor createAccessor(IModificationOperationCallback modificationCallback,
            ISearchOperationCallback searchCallback) {
        LSMRTreeOpContext opCtx = createOpContext(modificationCallback, searchCallback);
        return new LSMTreeIndexAccessor(getLsmHarness(), opCtx, cursorFactory);
    }

    @Override
    public IIndexBulkLoader createBulkLoader(float fillLevel, boolean verifyInput, long numElementsHint)
            throws HyracksDataException {
        return new LSMRTreeWithAntiMatterTuplesBulkLoader(fillLevel, verifyInput, numElementsHint);
    }

    public class LSMRTreeWithAntiMatterTuplesBulkLoader implements IIndexBulkLoader {
        private final ILSMDiskComponent component;
        private final IIndexBulkLoader bulkLoader;
        private boolean cleanedUpArtifacts = false;
        private boolean isEmptyComponent = true;
        public final PermutingTupleReference indexTuple;
        public final PermutingTupleReference filterTuple;
        public final MultiComparator filterCmp;

        public LSMRTreeWithAntiMatterTuplesBulkLoader(float fillFactor, boolean verifyInput, long numElementsHint)
                throws HyracksDataException {
            // Note that by using a flush target file name, we state that the
            // new bulk loaded tree is "newer" than any other merged tree.

            component = createBulkLoadTarget();
            bulkLoader = ((LSMRTreeDiskComponent) component).getRTree().createBulkLoader(fillFactor, verifyInput,
                    numElementsHint, false);

            if (getFilterFields() != null) {
                indexTuple = new PermutingTupleReference(getTreeFields());
                filterCmp = MultiComparator.create(component.getLSMComponentFilter().getFilterCmpFactories());
                filterTuple = new PermutingTupleReference(getFilterFields());
            } else {
                indexTuple = null;
                filterCmp = null;
                filterTuple = null;
            }
        }

        @Override
        public void add(ITupleReference tuple) throws HyracksDataException {
            try {
                ITupleReference t;
                if (indexTuple != null) {
                    indexTuple.reset(tuple);
                    t = indexTuple;
                } else {
                    t = tuple;
                }

                bulkLoader.add(t);

                if (filterTuple != null) {
                    filterTuple.reset(tuple);
                    component.getLSMComponentFilter().update(filterTuple, filterCmp);
                }

            } catch (Exception e) {
                cleanupArtifacts();
                throw e;
            }
            if (isEmptyComponent) {
                isEmptyComponent = false;
            }
        }

        @Override
        public void end() throws HyracksDataException {
            if (!cleanedUpArtifacts) {

                if (component.getLSMComponentFilter() != null) {
                    getFilterManager().writeFilter(component.getLSMComponentFilter(),
                            ((LSMRTreeDiskComponent) component).getRTree());
                }
                bulkLoader.end();

                if (isEmptyComponent) {
                    cleanupArtifacts();
                } else {
                    ioOpCallback.afterOperation(LSMOperationType.FLUSH, null, component);
                    getLsmHarness().addBulkLoadedComponent(component);
                }
            }
        }

        @Override
        public void abort() throws HyracksDataException {
            if (bulkLoader != null) {
                bulkLoader.abort();
            }
        }

        protected void cleanupArtifacts() throws HyracksDataException {
            if (!cleanedUpArtifacts) {
                cleanedUpArtifacts = true;
                ((LSMRTreeDiskComponent) component).getRTree().deactivate();
                ((LSMRTreeDiskComponent) component).getRTree().destroy();
            }
        }

        private ILSMDiskComponent createBulkLoadTarget() throws HyracksDataException {
            LSMComponentFileReferences relFlushFileRefs = fileManager.getRelFlushFileReference();
            return createDiskComponent(bulkLoaComponentFactory, relFlushFileRefs.getInsertIndexFileReference(), null,
                    null, true);
        }

    }

    @Override
    public void markAsValid(ILSMDiskComponent lsmComponent) throws HyracksDataException {
        RTree rtree = ((LSMRTreeDiskComponent) lsmComponent).getRTree();
        markAsValidInternal(rtree);
    }

    @Override
    public Set<String> getLSMComponentPhysicalFiles(ILSMComponent lsmComponent) {
        Set<String> files = new HashSet<>();
        RTree rtree = ((LSMRTreeDiskComponent) lsmComponent).getRTree();
        files.add(rtree.getFileReference().getFile().getAbsolutePath());
        return files;
    }

    @Override
    protected ILSMIOOperation createFlushOperation(AbstractLSMIndexOperationContext opCtx,
            ILSMMemoryComponent flushingComponent, LSMComponentFileReferences componentFileRefs,
            ILSMIOOperationCallback callback) throws HyracksDataException {
        ILSMIndexAccessor accessor = new LSMTreeIndexAccessor(getLsmHarness(), opCtx, cursorFactory);
        return new LSMRTreeFlushOperation(accessor, flushingComponent, componentFileRefs.getInsertIndexFileReference(),
                null, null, callback, fileManager.getBaseDir());
    }

    @Override
    protected ILSMIOOperation createMergeOperation(AbstractLSMIndexOperationContext opCtx,
            List<ILSMComponent> mergingComponents, LSMComponentFileReferences mergeFileRefs,
            ILSMIOOperationCallback callback) throws HyracksDataException {
        boolean returnDeletedTuples = false;
        if (mergingComponents.get(mergingComponents.size() - 1) != diskComponents.get(diskComponents.size() - 1)) {
            returnDeletedTuples = true;
        }
        ITreeIndexCursor cursor = new LSMRTreeWithAntiMatterTuplesSearchCursor(opCtx, returnDeletedTuples);
        ILSMIndexAccessor accessor = new LSMTreeIndexAccessor(getLsmHarness(), opCtx, cursorFactory);
        return new MergeOperation(accessor, mergeFileRefs.getInsertIndexFileReference(), callback,
                fileManager.getBaseDir(), mergingComponents, cursor);
    }
}

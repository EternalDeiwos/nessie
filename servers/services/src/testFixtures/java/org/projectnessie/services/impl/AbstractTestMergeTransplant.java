/*
 * Copyright (C) 2023 Dremio
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.projectnessie.services.impl;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.InstanceOfAssertFactories.list;
import static org.assertj.core.api.InstanceOfAssertFactories.map;
import static org.assertj.core.data.MapEntry.entry;
import static org.projectnessie.model.CommitMeta.fromMessage;
import static org.projectnessie.model.FetchOption.MINIMAL;
import static org.projectnessie.model.MergeBehavior.DROP;
import static org.projectnessie.model.MergeBehavior.FORCE;
import static org.projectnessie.model.MergeBehavior.NORMAL;

import com.google.common.collect.ImmutableList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.EnumSource.Mode;
import org.junit.jupiter.params.provider.ValueSource;
import org.projectnessie.error.BaseNessieClientServerException;
import org.projectnessie.error.NessieConflictException;
import org.projectnessie.error.NessieNotFoundException;
import org.projectnessie.error.NessieReferenceConflictException;
import org.projectnessie.model.Branch;
import org.projectnessie.model.CommitMeta;
import org.projectnessie.model.ContentKey;
import org.projectnessie.model.EntriesResponse;
import org.projectnessie.model.IcebergTable;
import org.projectnessie.model.LogResponse.LogEntry;
import org.projectnessie.model.MergeKeyBehavior;
import org.projectnessie.model.MergeResponse;
import org.projectnessie.model.MergeResponse.ContentKeyConflict;
import org.projectnessie.model.MergeResponse.ContentKeyDetails;
import org.projectnessie.model.Namespace;
import org.projectnessie.model.Operation.Put;
import org.projectnessie.model.Reference;

public abstract class AbstractTestMergeTransplant extends BaseTestServiceImpl {

  private static final ContentKey KEY_1 = ContentKey.of("both-added1");
  private static final ContentKey KEY_2 = ContentKey.of("both-added2");
  private static final ContentKey KEY_3 = ContentKey.of("branch-added");

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  public void transplantKeepCommits(boolean withDetachedCommit)
      throws BaseNessieClientServerException {
    testTransplant(withDetachedCommit, true);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  public void transplantSquashed(boolean withDetachedCommit)
      throws BaseNessieClientServerException {
    testTransplant(withDetachedCommit, false);
  }

  private void testTransplant(boolean withDetachedCommit, boolean keepIndividualCommits)
      throws BaseNessieClientServerException {
    mergeTransplant(
        false,
        keepIndividualCommits,
        (target, source, committed1, committed2, returnConflictAsResult) ->
            treeApi()
                .transplantCommitsIntoBranch(
                    target.getName(),
                    target.getHash(),
                    null,
                    ImmutableList.of(
                        requireNonNull(committed1.getHash()), requireNonNull(committed2.getHash())),
                    maybeAsDetachedName(withDetachedCommit, source),
                    keepIndividualCommits,
                    emptyList(),
                    NORMAL,
                    false,
                    false,
                    returnConflictAsResult));
  }

  @ParameterizedTest
  @EnumSource(names = {"UNCHANGED", "DETACHED"}) // hash is required
  public void mergeKeepCommits(ReferenceMode refMode) throws BaseNessieClientServerException {
    testMerge(refMode, true);
  }

  @ParameterizedTest
  @EnumSource(names = {"UNCHANGED", "DETACHED"}) // hash is required
  public void mergeSquashed(ReferenceMode refMode) throws BaseNessieClientServerException {
    testMerge(refMode, false);
  }

  private void testMerge(ReferenceMode refMode, boolean keepIndividualCommits)
      throws BaseNessieClientServerException {
    mergeTransplant(
        !keepIndividualCommits,
        keepIndividualCommits,
        (target, source, committed1, committed2, returnConflictAsResult) -> {
          Reference fromRef = refMode.transform(committed2);
          return treeApi()
              .mergeRefIntoBranch(
                  target.getName(),
                  target.getHash(),
                  fromRef.getName(),
                  fromRef.getHash(),
                  keepIndividualCommits,
                  null,
                  emptyList(),
                  NORMAL,
                  false,
                  false,
                  returnConflictAsResult);
        });
  }

  @FunctionalInterface
  interface MergeTransplantActor {
    MergeResponse act(
        Branch target,
        Branch source,
        Branch committed1,
        Branch committed2,
        boolean returnConflictAsResult)
        throws NessieNotFoundException, NessieConflictException;
  }

  private void mergeTransplant(
      boolean verifyAdditionalParents, boolean keepIndividualCommits, MergeTransplantActor actor)
      throws BaseNessieClientServerException {
    Branch root = createBranch("root");

    root =
        commit(
                root,
                fromMessage("root"),
                Put.of(ContentKey.of("other"), IcebergTable.of("/dev/null", 42, 42, 42, 42)))
            .getTargetBranch();

    Branch target = createBranch("base", root);
    Branch source = createBranch("branch", root);

    ContentKey key1 = ContentKey.of("key1");
    IcebergTable table1 = IcebergTable.of("table1", 42, 42, 42, 42);
    ContentKey key2 = ContentKey.of("key2");
    IcebergTable table2 = IcebergTable.of("table2", 43, 43, 43, 43);

    Branch committed1 =
        commit(source, fromMessage("test-branch1"), Put.of(key1, table1)).getTargetBranch();
    soft.assertThat(committed1.getHash()).isNotNull();

    table1 =
        (IcebergTable)
            contentApi().getContent(key1, committed1.getName(), committed1.getHash()).getContent();

    Branch committed2 =
        commit(
                source.getName(),
                committed1.getHash(),
                fromMessage("test-branch2"),
                Put.of(key1, table1, table1))
            .getTargetBranch();
    soft.assertThat(committed2.getHash()).isNotNull();

    int commitCount = 2;

    List<LogEntry> logBranch = commitLog(source.getName(), MINIMAL, source.getHash(), null, null);

    Branch baseHead =
        commit(target, fromMessage("test-main"), Put.of(key2, table2)).getTargetBranch();

    MergeResponse response = actor.act(target, source, committed1, committed2, false);
    Reference newHead =
        mergeWentFine(target, source, key1, committed1, committed2, baseHead, response);

    // try again --> conflict

    soft.assertThatThrownBy(() -> actor.act(target, source, committed1, committed2, false))
        .isInstanceOf(NessieReferenceConflictException.class)
        .hasMessageContaining("keys have been changed in conflict");

    // try again --> conflict, but return information

    conflictExceptionReturnedAsMergeResult(
        actor, target, source, key1, committed1, committed2, newHead);

    List<LogEntry> log = commitLog(target.getName(), MINIMAL, target.getHash(), null, null);
    if (keepIndividualCommits) {
      soft.assertThat(log.stream().map(LogEntry::getCommitMeta).map(CommitMeta::getMessage))
          .containsExactly("test-branch2", "test-branch1", "test-main", "root");
    } else {
      soft.assertThat(log.stream().map(LogEntry::getCommitMeta).map(CommitMeta::getMessage))
          .hasSize(3)
          .first(InstanceOfAssertFactories.STRING)
          .contains("test-branch2")
          .contains("test-branch1");
    }

    // Verify that the commit-timestamp was updated
    List<LogEntry> logOfMerged =
        commitLog(target.getName()).stream().limit(commitCount).collect(Collectors.toList());
    soft.assertThat(
            logOfMerged.stream().map(LogEntry::getCommitMeta).map(CommitMeta::getCommitTime))
        .isNotEqualTo(
            logBranch.stream().map(LogEntry::getCommitMeta).map(CommitMeta::getCommitTime));

    soft.assertThat(entries(target.getName(), null).stream().map(e -> e.getName().getName()))
        .containsExactlyInAnyOrder("other", "key1", "key2");

    if (verifyAdditionalParents) {
      soft.assertThat(logOfMerged)
          .first()
          .extracting(LogEntry::getAdditionalParents)
          .asInstanceOf(list(String.class))
          .isEmpty();
      soft.assertThat(logOfMerged)
          .first()
          .extracting(LogEntry::getCommitMeta)
          .extracting(CommitMeta::getParentCommitHashes)
          .asInstanceOf(list(String.class))
          .containsExactly(baseHead.getHash(), committed2.getHash());
      soft.assertThat(logOfMerged)
          .first()
          .extracting(LogEntry::getCommitMeta)
          .extracting(CommitMeta::getProperties)
          .asInstanceOf(map(String.class, String.class))
          .containsExactly(entry(CommitMeta.MERGE_PARENT_PROPERTY, committed2.getHash()));
    }
  }

  @SuppressWarnings("deprecation")
  private Reference mergeWentFine(
      Branch target,
      Branch source,
      ContentKey key1,
      Branch committed1,
      Branch committed2,
      Branch baseHead,
      MergeResponse response)
      throws NessieNotFoundException {
    Reference newHead = getReference(target.getName());
    soft.assertThat(response)
        .extracting(
            MergeResponse::wasApplied,
            MergeResponse::wasSuccessful,
            MergeResponse::getExpectedHash,
            MergeResponse::getTargetBranch,
            MergeResponse::getEffectiveTargetHash,
            MergeResponse::getResultantTargetHash)
        .containsExactly(
            true, true, source.getHash(), target.getName(), baseHead.getHash(), newHead.getHash());

    soft.assertThat(response.getCommonAncestor())
        .satisfiesAnyOf(
            a -> assertThat(a).isNull(), b -> assertThat(b).isEqualTo(target.getHash()));
    soft.assertThat(response.getDetails())
        .asInstanceOf(list(ContentKeyDetails.class))
        .extracting(
            ContentKeyDetails::getKey,
            ContentKeyDetails::getConflictType,
            ContentKeyDetails::getMergeBehavior)
        .contains(tuple(key1, ContentKeyConflict.NONE, NORMAL));
    if (response.getSourceCommits() != null && !response.getSourceCommits().isEmpty()) {
      // Database adapter
      soft.assertThat(response.getSourceCommits())
          .asInstanceOf(list(LogEntry.class))
          .extracting(LogEntry::getCommitMeta)
          .extracting(CommitMeta::getHash, CommitMeta::getMessage)
          .containsExactly(
              tuple(committed2.getHash(), "test-branch2"),
              tuple(committed1.getHash(), "test-branch1"));
    }
    if (response.getTargetCommits() != null) {
      // Database adapter
      soft.assertThat(response.getTargetCommits())
          .asInstanceOf(list(LogEntry.class))
          .extracting(LogEntry::getCommitMeta)
          .extracting(CommitMeta::getHash, CommitMeta::getMessage)
          .contains(tuple(baseHead.getHash(), "test-main"));
    }

    return newHead;
  }

  @SuppressWarnings("deprecation")
  private void conflictExceptionReturnedAsMergeResult(
      MergeTransplantActor actor,
      Branch target,
      Branch source,
      ContentKey key1,
      Branch committed1,
      Branch committed2,
      Reference newHead)
      throws NessieNotFoundException, NessieConflictException {
    MergeResponse conflictResult = actor.act(target, source, committed1, committed2, true);

    soft.assertThat(conflictResult)
        .extracting(
            MergeResponse::wasApplied,
            MergeResponse::wasSuccessful,
            MergeResponse::getExpectedHash,
            MergeResponse::getTargetBranch,
            MergeResponse::getEffectiveTargetHash)
        .containsExactly(false, false, source.getHash(), target.getName(), newHead.getHash());

    soft.assertThat(conflictResult.getCommonAncestor())
        .satisfiesAnyOf(
            a -> assertThat(a).isNull(), b -> assertThat(b).isEqualTo(target.getHash()));
    soft.assertThat(conflictResult.getDetails())
        .asInstanceOf(list(ContentKeyDetails.class))
        .extracting(
            ContentKeyDetails::getKey,
            ContentKeyDetails::getConflictType,
            ContentKeyDetails::getMergeBehavior)
        .contains(tuple(key1, ContentKeyConflict.UNRESOLVABLE, NORMAL));
    if (conflictResult.getSourceCommits() != null && !conflictResult.getSourceCommits().isEmpty()) {
      // Database adapter
      soft.assertThat(conflictResult.getSourceCommits())
          .asInstanceOf(list(LogEntry.class))
          .extracting(LogEntry::getCommitMeta)
          .extracting(CommitMeta::getHash, CommitMeta::getMessage)
          .containsExactly(
              tuple(committed2.getHash(), "test-branch2"),
              tuple(committed1.getHash(), "test-branch1"));
    }
    if (conflictResult.getTargetCommits() != null) {
      // Database adapter
      soft.assertThat(conflictResult.getTargetCommits())
          .asInstanceOf(list(LogEntry.class))
          .extracting(LogEntry::getCommitMeta)
          .extracting(CommitMeta::getMessage)
          .containsAnyOf("test-branch2", "test-branch1", "test-main");
    }
  }

  @Test
  public void mergeMessage() throws BaseNessieClientServerException {
    testMergeTransplantMessage(
        (target, source, committed1, committed2, returnConflictAsResult) ->
            treeApi()
                .mergeRefIntoBranch(
                    target.getName(),
                    target.getHash(),
                    source.getName(),
                    source.getHash(),
                    false,
                    "test-message-override-123",
                    emptyList(),
                    NORMAL,
                    false,
                    false,
                    returnConflictAsResult),
        ImmutableList.of("test-message-override-123"));
  }

  @Test
  public void mergeMessageDefault() throws BaseNessieClientServerException {
    testMergeTransplantMessage(
        (target, source, committed1, committed2, returnConflictAsResult) ->
            treeApi()
                .mergeRefIntoBranch(
                    target.getName(),
                    target.getHash(),
                    source.getName(),
                    source.getHash(),
                    false,
                    null,
                    emptyList(),
                    NORMAL,
                    false,
                    false,
                    returnConflictAsResult),
        ImmutableList.of(
            "test-commit-1\n---------------------------------------------\ntest-commit-2"));
  }

  @Test
  public void transplantMessageSquashed() throws BaseNessieClientServerException {
    testMergeTransplantMessage(
        (target, source, committed1, committed2, returnConflictAsResult) ->
            treeApi()
                .transplantCommitsIntoBranch(
                    target.getName(),
                    target.getHash(),
                    "test-message-override-123",
                    ImmutableList.of(
                        requireNonNull(committed1.getHash()), requireNonNull(committed2.getHash())),
                    source.getName(),
                    false,
                    emptyList(),
                    NORMAL,
                    false,
                    false,
                    returnConflictAsResult),
        ImmutableList.of("test-message-override-123"));
  }

  @Test
  public void transplantMessageSingle() throws BaseNessieClientServerException {
    testMergeTransplantMessage(
        (target, source, committed1, committed2, returnConflictAsResult) ->
            treeApi()
                .transplantCommitsIntoBranch(
                    target.getName(),
                    target.getHash(),
                    "test-message-override-123",
                    ImmutableList.of(requireNonNull(committed1.getHash())),
                    source.getName(),
                    false,
                    emptyList(),
                    NORMAL,
                    false,
                    false,
                    returnConflictAsResult),
        ImmutableList.of("test-message-override-123"));
  }

  @Test
  public void transplantMessageOverrideMultiple() throws BaseNessieClientServerException {
    testMergeTransplantMessage(
        (target, source, committed1, committed2, returnConflictAsResult) ->
            treeApi()
                .transplantCommitsIntoBranch(
                    target.getName(),
                    target.getHash(),
                    "ignored-message-override",
                    ImmutableList.of(
                        requireNonNull(committed1.getHash()), requireNonNull(committed2.getHash())),
                    source.getName(),
                    true,
                    emptyList(),
                    NORMAL,
                    false,
                    false,
                    returnConflictAsResult),
        // Note: the expected messages are given in the commit log order (newest to oldest)
        ImmutableList.of("test-commit-2", "test-commit-1"));
  }

  private void testMergeTransplantMessage(
      MergeTransplantActor actor, Collection<String> expectedMessages)
      throws BaseNessieClientServerException {
    Branch target = createBranch("merge-transplant-msg-target");

    // Common ancestor
    target =
        commit(
                target,
                fromMessage("test-root"),
                Put.of(
                    ContentKey.of("irrelevant-to-this-test"),
                    IcebergTable.of("something", 42, 43, 44, 45)))
            .getTargetBranch();

    Branch source = createBranch("merge-transplant-msg-source", target);

    ContentKey key1 = ContentKey.of("test-key1");
    ContentKey key2 = ContentKey.of("test-key2");

    source =
        commit(
                source,
                fromMessage("test-commit-1"),
                Put.of(key1, IcebergTable.of("table1", 42, 43, 44, 45)))
            .getTargetBranch();

    Branch firstCommitOnSource = source;

    source =
        commit(
                source,
                fromMessage("test-commit-2"),
                Put.of(key2, IcebergTable.of("table2", 42, 43, 44, 45)))
            .getTargetBranch();

    actor.act(target, source, firstCommitOnSource, source, false);

    soft.assertThat(commitLog(target.getName()).stream().limit(expectedMessages.size()))
        .isNotEmpty()
        .extracting(e -> e.getCommitMeta().getMessage())
        .containsExactlyElementsOf(expectedMessages);
  }

  @ParameterizedTest
  @EnumSource(
      value = ReferenceMode.class,
      mode = Mode.EXCLUDE,
      names = "NAME_ONLY") // merge requires the hash
  public void mergeWithNamespaces(ReferenceMode refMode) throws BaseNessieClientServerException {
    Branch root = createBranch("root");

    // common ancestor commit
    ContentKey something = ContentKey.of("something");
    root =
        commit(
                root,
                fromMessage("test-branch1"),
                Put.of(something, IcebergTable.of("something", 42, 43, 44, 45)))
            .getTargetBranch();

    Branch base = createBranch("merge-base", root);
    Branch branch = createBranch("merge-branch", root);

    // create the same namespace on both branches
    Namespace ns = Namespace.parse("a.b.c");
    base = ensureNamespacesForKeysExist(base, ns.toContentKey());
    branch = ensureNamespacesForKeysExist(branch, ns.toContentKey());
    namespaceApi().createNamespace(base.getName(), ns);
    namespaceApi().createNamespace(branch.getName(), ns);

    base = (Branch) getReference(base.getName());
    branch = (Branch) getReference(branch.getName());

    IcebergTable table1 = IcebergTable.of("table1", 42, 42, 42, 42);
    IcebergTable table2 = IcebergTable.of("table2", 43, 43, 43, 43);

    ContentKey key1 = ContentKey.of(ns, "key1");
    ContentKey key2 = ContentKey.of(ns, "key2");
    Branch committed1 =
        commit(branch, fromMessage("test-branch1"), Put.of(key1, table1)).getTargetBranch();
    soft.assertThat(committed1.getHash()).isNotNull();

    table1 =
        (IcebergTable)
            contentApi().getContent(key1, committed1.getName(), committed1.getHash()).getContent();

    Branch committed2 =
        commit(committed1, fromMessage("test-branch2"), Put.of(key1, table1, table1))
            .getTargetBranch();
    soft.assertThat(committed2.getHash()).isNotNull();

    commit(base, fromMessage("test-main"), Put.of(key2, table2));

    Reference fromRef = refMode.transform(committed2);
    treeApi()
        .mergeRefIntoBranch(
            base.getName(),
            base.getHash(),
            fromRef.getName(),
            fromRef.getHash(),
            false,
            null,
            emptyList(),
            NORMAL,
            false,
            false,
            false);

    List<LogEntry> log = commitLog(base.getName(), MINIMAL, base.getHash(), null, null);
    soft.assertThat(
            log.stream().map(LogEntry::getCommitMeta).map(CommitMeta::getMessage).findFirst())
        .isPresent()
        .hasValueSatisfying(v -> assertThat(v).contains("test-branch1"))
        .hasValueSatisfying(v -> assertThat(v).contains("test-branch2"));

    soft.assertThat(
            withoutNamespaces(entries(base.getName(), null)).stream()
                .map(EntriesResponse.Entry::getName))
        .containsExactlyInAnyOrder(something, key1, key2);

    soft.assertThat(namespaceApi().getNamespace(base.getName(), null, ns)).isNotNull();
  }

  @Test
  public void mergeWithCustomModes() throws BaseNessieClientServerException {
    testMergeTransplantWithCustomModes(
        (target, source, committed1, committed2, returnConflictAsResult) ->
            treeApi()
                .mergeRefIntoBranch(
                    target.getName(),
                    target.getHash(),
                    source.getName(),
                    source.getHash(),
                    false,
                    null,
                    asList(MergeKeyBehavior.of(KEY_1, DROP), MergeKeyBehavior.of(KEY_3, NORMAL)),
                    FORCE,
                    false,
                    false,
                    returnConflictAsResult));
  }

  @Test
  public void transplantWithCustomModes() throws BaseNessieClientServerException {
    testMergeTransplantWithCustomModes(
        (target, source, committed1, committed2, returnConflictAsResult) ->
            treeApi()
                .transplantCommitsIntoBranch(
                    target.getName(),
                    target.getHash(),
                    null,
                    ImmutableList.of(
                        requireNonNull(committed1.getHash()), requireNonNull(committed2.getHash())),
                    source.getName(),
                    false,
                    asList(MergeKeyBehavior.of(KEY_1, DROP), MergeKeyBehavior.of(KEY_3, NORMAL)),
                    FORCE,
                    false,
                    false,
                    returnConflictAsResult));
  }

  private void testMergeTransplantWithCustomModes(MergeTransplantActor actor)
      throws BaseNessieClientServerException {
    Branch target = createBranch("target");

    // Common ancestor
    target =
        commit(
                target,
                fromMessage("test-root"),
                Put.of(
                    ContentKey.of("irrelevant-to-this-test"),
                    IcebergTable.of("something", 42, 43, 44, 45)))
            .getTargetBranch();

    Branch branch = createBranch("test-branch", target);

    target =
        commit(
                target,
                fromMessage("test-main"),
                Put.of(KEY_1, IcebergTable.of("main-table1", 42, 43, 44, 45)),
                Put.of(KEY_2, IcebergTable.of("main-table1", 42, 43, 44, 45)))
            .getTargetBranch();

    branch =
        commit(
                branch,
                fromMessage("test-fork"),
                Put.of(KEY_1, IcebergTable.of("branch-table1", 42, 43, 44, 45)),
                Put.of(KEY_2, IcebergTable.of("branch-table2", 42, 43, 44, 45)))
            .getTargetBranch();

    Branch firstCommitOnBranch = branch;

    branch =
        commit(
                branch,
                fromMessage("test-fork"),
                Put.of(KEY_3, IcebergTable.of("branch-no-conflict", 42, 43, 44, 45)))
            .getTargetBranch();

    MergeResponse response = actor.act(target, branch, firstCommitOnBranch, branch, false);

    soft.assertThat(response.getDetails())
        .asInstanceOf(list(ContentKeyDetails.class))
        .extracting(
            ContentKeyDetails::getKey,
            ContentKeyDetails::getConflictType,
            ContentKeyDetails::getMergeBehavior)
        .containsExactlyInAnyOrder(
            tuple(KEY_1, ContentKeyConflict.NONE, DROP),
            tuple(KEY_2, ContentKeyConflict.NONE, FORCE),
            tuple(KEY_3, ContentKeyConflict.NONE, NORMAL));

    soft.assertThat(
            contents(target.getName(), response.getResultantTargetHash(), KEY_1, KEY_2, KEY_3)
                .entrySet())
        .extracting(Map.Entry::getKey, e -> ((IcebergTable) e.getValue()).getMetadataLocation())
        .containsExactlyInAnyOrder(
            tuple(KEY_1, "main-table1"),
            tuple(KEY_2, "branch-table2"),
            tuple(KEY_3, "branch-no-conflict"));
  }
}
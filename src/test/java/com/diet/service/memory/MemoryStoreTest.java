package com.diet.service.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class MemoryStoreTest extends MemoryDatabaseFixture {
    @Test void messageAndDurableTaskCommitTogetherAndRejectForeignSession() {
        var job=store.appendTurn(1L,"s1","我喜欢清淡","t1");
        assertThat(job.message()).isEqualTo("我喜欢清淡");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM diet_messages",Integer.class)).isEqualTo(1);
        assertThat(store.pending(10)).extracting(MemoryJob::id).containsExactly(job.id());
        assertThatThrownBy(()->store.appendTurn(2L,"s1","不该写入","t2")).isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM diet_messages",Integer.class)).isEqualTo(1);
    }
    @Test void messageInsertRollsBackWhenTaskInsertFails() {
        jdbc.execute("DROP TABLE user_memory_job");
        assertThatThrownBy(()->store.appendTurn(1L,"s1","我喜欢清淡","t1")).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM diet_messages",Integer.class)).isZero();
    }
    @Test void confirmationAndDeletionAreVersionedAndIsolated() {
        var job=store.appendTurn(1L,"s1","我对花生过敏","t1");
        var item=store.remember(job,"ALLERGEN","花生","NEUTRAL","EXPLICIT","我对花生过敏",false);
        assertThat(item.status()).isEqualTo("PENDING");
        assertThat(store.list(2L)).isEmpty();
        assertThatThrownBy(()->store.change(2L,item.id(),item.version(),"CONFIRM",null,null)).isInstanceOf(IllegalArgumentException.class);
        var active=store.change(1L,item.id(),item.version(),"CONFIRM",null,null);
        assertThat(active.status()).isEqualTo("ACTIVE");
        assertThatThrownBy(()->store.change(1L,item.id(),item.version(),"DELETE",null,null)).isInstanceOf(IllegalArgumentException.class);
        store.change(1L,item.id(),active.version(),"DELETE",null,null);
        assertThat(store.remember(job,"ALLERGEN","花生","NEUTRAL","EXPLICIT","我对花生过敏",false)).isNull();
        assertThat(store.list(1L)).isEmpty();
    }
    @Test void repeatedEvidenceDoesNotIncreaseCountAndLeasePreventsDuplicateWorkers() {
        var job=store.appendTurn(1L,"s1","我平时喜欢清淡","t1");
        store.remember(job,"TASTE","清淡","LIKE","EXPLICIT","我平时喜欢清淡",false);
        store.remember(job,"TASTE","清淡","LIKE","EXPLICIT","我平时喜欢清淡",false);
        assertThat(store.list(1L)).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_memory_evidence",Integer.class)).isEqualTo(1);
        String token=store.claim(job.id());
        assertThat(token).isNotBlank();
        assertThat(store.claim(job.id())).isNull();
        assertThat(store.complete(job.id(),"wrong-token")).isFalse();
        assertThat(store.complete(job.id(),token)).isTrue();
        assertThat(store.pending(10)).isEmpty();
    }
    @Test void disablingCollectionInvalidatesOldTasksButKeepsExistingMemory() {
        var job=store.appendTurn(1L,"s1","喜欢清淡","t1");
        store.remember(job,"TASTE","清淡","LIKE","EXPLICIT","喜欢清淡",false);
        store.setCollection(1L,false);
        assertThat(store.remember(job,"CUISINE","川菜","LIKE","EXPLICIT","川菜",false)).isNull();
        assertThat(store.list(1L)).hasSize(1);
        store.appendTurn(1L,"s1","以后喜欢粤菜","t2");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_memory_job",Integer.class)).isEqualTo(1);
    }
    @Test void aCrashedFinalAttemptBecomesFailedInsteadOfBeingPolledForever() {
        var job=store.appendTurn(1L,"s1","我平时喜欢清淡","t1");
        jdbc.update("UPDATE user_memory_job SET status='RUNNING',attempts=5,lease_until=0 WHERE id=?",job.id());
        assertThat(store.pending(10)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT status FROM user_memory_job WHERE id=?",String.class,job.id())).isEqualTo("FAILED");
    }
    @Test void newerRemovalCreatesTombstoneEvenIfOlderAddHasNotRun() {
        var older=store.appendTurn(1L,"s1","我平时喜欢清淡","t1");
        var newer=store.appendTurn(1L,"s1","忘记我喜欢清淡","t2");
        var token=store.claim(newer.id());
        store.apply(newer,token,List.of(new MemoryFact("TASTE","清淡","LIKE","LONG_TERM","REMOVE","忘记我喜欢清淡")));
        assertThat(store.remember(older,"TASTE","清淡","LIKE","EXPLICIT","我平时喜欢清淡",false)).isNull();
        assertThat(store.list(1L)).isEmpty();
    }
    @Test void aNewExplicitStatementMayReAddAfterUserDeletion() {
        var first=store.appendTurn(1L,"s1","我喜欢清淡","t1");
        var saved=store.remember(first,"TASTE","清淡","LIKE","EXPLICIT","我喜欢清淡",false);
        store.change(1L,saved.id(),saved.version(),"DELETE",null,null);
        var fresh=store.appendTurn(1L,"s1","我又喜欢清淡了，请记住","t2");
        assertThat(store.remember(fresh,"TASTE","清淡","LIKE","EXPLICIT","我又喜欢清淡了，请记住",false)).isNotNull();
        assertThat(store.list(1L)).hasSize(1);
    }
    @Test void manualCorrectionsCanReturnToAPreviousValueAndKeepTheirOwnEvidence() {
        var job=store.appendTurn(1L,"s1","我喜欢川菜","t1");
        var a=store.remember(job,"CUISINE","川菜","LIKE","EXPLICIT","我喜欢川菜",false);
        var b=store.change(1L,a.id(),a.version(),"UPDATE","粤菜","LIKE");
        assertThat(b.evidence()).contains("粤菜");
        assertThat(b.sourceMessageId()).isNull();
        var restored=store.change(1L,b.id(),b.version(),"UPDATE","川菜","LIKE");
        assertThat(store.list(1L)).extracting(MemoryItem::value).containsExactly("川菜");
        assertThat(restored.evidence()).contains("川菜");
    }
}

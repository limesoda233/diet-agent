package com.diet.service.memory;

import com.diet.service.memory.extraction.MemoryCandidate;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class MemoryServiceTest extends MemoryDatabaseFixture {
    MemoryCandidate candidate(String type,String value,String subject,String stance,String scope,String evidence) {
        return new MemoryCandidate(type,value,subject,stance,scope,"ADD",evidence);
    }
    @Test void ordinaryLongTermPreferencePersistsAcrossSessionsAndTemporaryDoesNot() {
        var service=new MemoryService(store,message->List.of(candidate("TASTE","清淡","SELF","LIKE","LONG_TERM",message)));
        var turn=service.prepare(1L,"s1","我平时喜欢清淡","t1");
        assertThat(turn.notices()).anyMatch(t->t.contains("清淡"));
        assertThat(store.list(1L)).extracting(MemoryItem::value).containsExactly("清淡");
        jdbc.update("INSERT INTO diet_sessions VALUES ('s3',1)");
        var temporary=new MemoryService(store,message->List.of(candidate("TASTE","辣","SELF","LIKE","TEMPORARY",message)));
        var next=temporary.prepare(1L,"s3","今天吃辣","t2");
        assertThat(next.items()).extracting(MemoryItem::value).contains("清淡");
        assertThat(store.list(1L)).extracting(MemoryItem::value).doesNotContain("辣");
    }
    @Test void allergyAppliesBeforeConfirmationAndCannotBeSilentlyRemoved() {
        var service=new MemoryService(store,message->List.of(candidate("ALLERGEN","花生","SELF","NEUTRAL","LONG_TERM",message)));
        var turn=service.prepare(1L,"s1","我对花生过敏","t1");
        assertThat(turn.hasAllergy()).isTrue();
        assertThat(turn.items()).extracting(MemoryItem::status).containsExactly("PENDING");
        var removed=new MemoryService(store,message->List.of(new MemoryCandidate("ALLERGEN","花生","SELF","NEUTRAL","LONG_TERM","REMOVE",message)));
        assertThat(removed.prepare(1L,"s1","我不过敏了","t2").hasAllergy()).isTrue();
    }
    @Test void otherPeopleHypotheticalsAndUnsupportedEvidenceNeverWrite() {
        var other=new MemoryService(store,m->List.of(candidate("ALLERGEN","花生","OTHER","NEUTRAL","LONG_TERM",m)));
        assertThat(other.prepare(1L,"s1","我朋友对花生过敏","t1").hasAllergy()).isFalse();
        var hypothetical=new MemoryService(store,m->List.of(candidate("ALLERGEN","花生","SELF","NEUTRAL","LONG_TERM",m)));
        assertThat(hypothetical.prepare(1L,"s1","如果我对花生过敏怎么办","t2").hasAllergy()).isFalse();
        var bad=new MemoryService(store,m->List.of(candidate("TASTE","清淡","SELF","LIKE","LONG_TERM","不存在的原文")));
        assertThat(bad.prepare(1L,"s1","吃啥","t3").notices()).noneMatch(t->t.contains("已记住"));
        assertThat(store.list(1L)).isEmpty();
    }
    @Test void extractionFailureQueuesRetryAndSafetyLanguageRequiresClarification() {
        var service=new MemoryService(store,m->{throw new IllegalStateException("offline");});
        var turn=service.prepare(1L,"s1","我对某种坚果过敏","t1");
        assertThat(turn.requiresClarification()).isTrue();
        assertThat(turn.notices()).noneMatch(t->t.contains("已记住"));
        assertThat(jdbc.queryForObject("SELECT status FROM user_memory_job",String.class)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT attempts FROM user_memory_job",Integer.class)).isEqualTo(1);
    }
    @Test void anEmptyModelAnswerCannotSilentlyDropAnExplicitAllergy() {
        var service=new MemoryService(store,m->List.of());
        assertThat(service.prepare(1L,"s1","我对澳洲坚果过敏","t1").requiresClarification()).isTrue();
        assertThat(service.prepare(1L,"s1","我吃花生会过敏，推荐晚餐","t2").requiresClarification()).isTrue();
        assertThat(service.prepare(1L,"s1","我对花生过敏，如果没有鸡肉可以换牛肉吗","t3").requiresClarification()).isTrue();
    }
    @Test void inferredPreferenceNeedsIndependentSessionsAndNeverOverridesExplicit() {
        var temporary=new MemoryService(store,m->List.of(candidate("TASTE","清淡","SELF","LIKE","TEMPORARY",m)));
        temporary.prepare(1L,"s1","今天想吃清淡","t1");
        temporary.prepare(1L,"s1","清淡点","t2");
        assertThat(store.consolidate(0,2)).isZero();
        jdbc.update("INSERT INTO diet_sessions VALUES ('s3',1)");
        temporary.prepare(1L,"s3","来点清淡","t3");
        assertThat(store.consolidate(0,2)).isEqualTo(1);
        assertThat(store.list(1L).get(0).sourceType()).isEqualTo("INFERRED");
        var explicit=new MemoryService(store,m->List.of(candidate("TASTE","清淡","SELF","DISLIKE","LONG_TERM",m)));
        explicit.prepare(1L,"s1","我一直不喜欢清淡","t4");
        assertThat(store.consolidate(0,2)).isZero();
        assertThat(store.list(1L).get(0).stance()).isEqualTo("DISLIKE");
    }
}

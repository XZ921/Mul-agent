package cn.bugstack.competitoragent.rag;

import cn.bugstack.competitoragent.repository.RetrievalChunkRepository;
import cn.bugstack.competitoragent.repository.RetrievalIndexRepository;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RetrievalScopeRepositoryContractTest {

    @Test
    void shouldExposeThreeLevelRecallQueryEntrypointsOnRepositories() {
        // Task 5.3.a 的完成标志不是“表里多了几个字段”就算结束，
        // 而是仓储层已经把 Task / Domain / Organization 三层边界
        // 提升为正式查询入口，后续召回服务才能明确按作用域治理，而不是继续退回 taskId 旁路。
        assertRepositoryMethods(
                RetrievalIndexRepository.class,
                List.of(
                        "findByRetrievalScopeAndScopeRefKeyOrderByIdAsc",
                        "findByRetrievalScopeAndKnowledgeDomainKeyOrderByIdAsc"
                )
        );
        assertRepositoryMethods(
                RetrievalChunkRepository.class,
                List.of(
                        "findByRetrievalScopeAndScopeRefKeyOrderByKnowledgeDocumentIdAscChunkIndexAsc",
                        "findByRetrievalScopeAndKnowledgeDomainKeyOrderByKnowledgeDocumentIdAscChunkIndexAsc"
                )
        );
    }

    private void assertRepositoryMethods(Class<?> repositoryType, List<String> methodNames) {
        List<String> publicMethodNames = Arrays.stream(repositoryType.getMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(Method::getName)
                .toList();
        assertTrue(publicMethodNames.containsAll(methodNames),
                repositoryType.getSimpleName() + " 应暴露三层召回边界查询方法");
    }
}

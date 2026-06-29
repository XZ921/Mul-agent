package cn.bugstack.competitoragent.source;

/**
 * 搜索请求阶段语义。
 * 这里只区分 Phase 1 bootstrap 与运行期 supplement，
 * 避免继续使用裸字符串在 provider 链路里传递阶段信息。
 */
public enum SearchRequestPhase {
    BOOTSTRAP,
    SUPPLEMENT
}

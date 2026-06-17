package cn.bugstack.competitoragent.collection;

/**
 * 网页采集执行提示。
 * LIGHTWEIGHT 代表优先走轻量正文读取路径；
 * FULL_RENDER 代表必须进入浏览器完整渲染；
 * LOGIN_REQUIRED / INTERACTION_REQUIRED / ANTI_BOT_RISK_HIGH 先作为正式枚举保留，
 * 避免后续继续在链路里散落字符串判断。
 */
public enum WebPageRenderHint {

    LIGHTWEIGHT,
    FULL_RENDER,
    LOGIN_REQUIRED,
    INTERACTION_REQUIRED,
    ANTI_BOT_RISK_HIGH
}

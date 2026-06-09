import type {
  ContentEvidenceFragmentInfo,
  DiagnosisItemInfo,
  DiagnosisSectionInfo,
  EvidenceReferenceInfo,
  FieldEvidenceDetailInfo,
  RevisionDirectiveInfo,
  QualityDiagnosisInfo,
  ReportDiagnosisInfo,
  ReviewNextAction,
  SectionEvidenceBundleInfo,
} from '../types'

function asRecord(value: unknown): Record<string, unknown> | null {
  return value && typeof value === 'object' && !Array.isArray(value) ? (value as Record<string, unknown>) : null
}

function normalizeObjectArray<T>(value: unknown): T[] {
  return Array.isArray(value) ? (value as T[]) : []
}

function normalizeStringArray(value: unknown): string[] {
  return Array.isArray(value) ? value.map((item) => String(item).trim()).filter(Boolean) : []
}

function normalizeNullableText(value: unknown) {
  return typeof value === 'string' && value.trim() ? value : null
}

function normalizeDiagnosisPayload(value: unknown): QualityDiagnosisInfo {
  const record = asRecord(value)
  return {
    ...(record as QualityDiagnosisInfo | null),
    type: typeof record?.type === 'string' && record.type.trim() ? record.type : 'unknown_diagnosis',
    section: typeof record?.section === 'string' && record.section.trim() ? record.section : 'general',
    severity: typeof record?.severity === 'string' ? record.severity : undefined,
    level: typeof record?.level === 'string' ? record.level : undefined,
    title: typeof record?.title === 'string' ? record.title : null,
    detail: typeof record?.detail === 'string' ? record.detail : null,
    evidenceBasis: typeof record?.evidenceBasis === 'string' ? record.evidenceBasis : null,
    repairSuggestion: typeof record?.repairSuggestion === 'string' ? record.repairSuggestion : null,
    evidenceIds: normalizeStringArray(record?.evidenceIds),
    sourceUrls: normalizeStringArray(record?.sourceUrls),
  }
}

function normalizeEvidenceReference(value: unknown): EvidenceReferenceInfo {
  const record = asRecord(value)
  return {
    ...(record as EvidenceReferenceInfo | null),
    evidenceId: typeof record?.evidenceId === 'string' ? record.evidenceId : null,
    title: typeof record?.title === 'string' ? record.title : null,
    url: typeof record?.url === 'string' ? record.url : null,
    competitorName: typeof record?.competitorName === 'string' ? record.competitorName : null,
    sourceType: typeof record?.sourceType === 'string' ? record.sourceType : null,
    contentSnippet: typeof record?.contentSnippet === 'string' ? record.contentSnippet : null,
  }
}

function normalizeDiagnosisItem(value: unknown): DiagnosisItemInfo {
  const record = asRecord(value)
  return {
    reviewStage: typeof record?.reviewStage === 'string' ? record.reviewStage : 'REPORT',
    diagnosis: normalizeDiagnosisPayload(record?.diagnosis),
    evidenceReferences: normalizeObjectArray(record?.evidenceReferences).map(normalizeEvidenceReference),
  }
}

function normalizeDiagnosisSection(value: unknown): DiagnosisSectionInfo {
  const record = asRecord(value)
  return {
    section: typeof record?.section === 'string' && record.section.trim() ? record.section : 'general',
    evidenceInsufficient: record?.evidenceInsufficient === true,
    sourceUrls: normalizeStringArray(record?.sourceUrls),
    repairSuggestions: normalizeStringArray(record?.repairSuggestions),
    diagnoses: normalizeObjectArray(record?.diagnoses).map(normalizeDiagnosisItem),
  }
}

function normalizeContentEvidence(value: unknown): ContentEvidenceFragmentInfo {
  const record = asRecord(value)
  return {
    ...(record as ContentEvidenceFragmentInfo | null),
    stage: typeof record?.stage === 'string' ? record.stage : null,
    competitorName: typeof record?.competitorName === 'string' ? record.competitorName : null,
    fieldName: typeof record?.fieldName === 'string' ? record.fieldName : null,
    evidenceId: typeof record?.evidenceId === 'string' ? record.evidenceId : null,
    sourceUrl: typeof record?.sourceUrl === 'string' ? record.sourceUrl : null,
    title: typeof record?.title === 'string' ? record.title : null,
    snippet: typeof record?.snippet === 'string' ? record.snippet : null,
    issueFlags: normalizeStringArray(record?.issueFlags),
    evidence: record?.evidence && typeof record.evidence === 'object'
      ? (record.evidence as ContentEvidenceFragmentInfo['evidence'])
      : null,
  }
}

function normalizeNextAction(value: unknown): ReviewNextAction {
  const record = asRecord(value)
  return {
    ...(record as ReviewNextAction | null),
    title: typeof record?.title === 'string' && record.title.trim() ? record.title : '处理诊断问题',
    description: typeof record?.description === 'string' ? record.description : '',
    actionType: typeof record?.actionType === 'string' && record.actionType.trim() ? record.actionType : 'MANUAL_REVIEW',
    targetNode: typeof record?.targetNode === 'string' ? record.targetNode : null,
    priority: typeof record?.priority === 'string' && record.priority.trim() ? record.priority : 'MEDIUM',
  }
}

function normalizeRevisionDirective(value: unknown): RevisionDirectiveInfo {
  const record = asRecord(value)
  return {
    ...(record as RevisionDirectiveInfo | null),
    category: normalizeNullableText(record?.category),
    actionType: normalizeNullableText(record?.actionType),
    priority: normalizeNullableText(record?.priority),
    targetNode: normalizeNullableText(record?.targetNode),
    targetSection: normalizeNullableText(record?.targetSection),
    summary: normalizeNullableText(record?.summary),
    searchFeedback: normalizeNullableText(record?.searchFeedback),
    searchQueries: normalizeStringArray(record?.searchQueries),
    sourceUrls: normalizeStringArray(record?.sourceUrls),
    expectedOutcome: normalizeNullableText(record?.expectedOutcome),
  }
}

function normalizeFieldEvidenceDetail(value: unknown): FieldEvidenceDetailInfo {
  const record = asRecord(value)
  return {
    ...(record as FieldEvidenceDetailInfo | null),
    fieldName: normalizeNullableText(record?.fieldName),
    fieldLabel: normalizeNullableText(record?.fieldLabel),
    coverageStatus: normalizeNullableText(record?.coverageStatus),
    gapComment: normalizeNullableText(record?.gapComment),
    evidenceId: normalizeNullableText(record?.evidenceId),
    sourceUrl: normalizeNullableText(record?.sourceUrl),
    title: normalizeNullableText(record?.title),
    snippet: normalizeNullableText(record?.snippet),
    issueFlags: normalizeStringArray(record?.issueFlags),
    evidence: record?.evidence && typeof record.evidence === 'object'
      ? normalizeEvidenceReference(record.evidence)
      : null,
  }
}

function normalizeSectionEvidenceBundle(value: unknown): SectionEvidenceBundleInfo {
  const record = asRecord(value)
  return {
    ...(record as SectionEvidenceBundleInfo | null),
    stage: normalizeNullableText(record?.stage),
    sectionType: normalizeNullableText(record?.sectionType),
    sectionKey: normalizeNullableText(record?.sectionKey),
    sectionTitle: normalizeNullableText(record?.sectionTitle),
    summary: normalizeNullableText(record?.summary),
    gapSummary: normalizeNullableText(record?.gapSummary),
    hasGap: record?.hasGap === true,
    fieldNames: normalizeStringArray(record?.fieldNames),
    missingFields: normalizeStringArray(record?.missingFields),
    sourceUrls: normalizeStringArray(record?.sourceUrls),
    issueFlags: normalizeStringArray(record?.issueFlags),
    fields: normalizeObjectArray(record?.fields).map(normalizeFieldEvidenceDetail),
    evidenceReferences: normalizeObjectArray(record?.evidenceReferences).map(normalizeEvidenceReference),
  }
}

/**
 * 诊断数据既可能来自完整报告接口，也可能来自运行中的 SSE 增量补齐。
 * 为了避免历史脏数据或半结构化事件把 React 渲染层直接打崩，这里统一把所有关键集合字段收敛成稳定数组。
 */
export function normalizeReportDiagnosis(diagnosis: ReportDiagnosisInfo | null | undefined): ReportDiagnosisInfo | null {
  if (!diagnosis) {
    return null
  }

  return {
    ...diagnosis,
    sourceUrls: normalizeStringArray(diagnosis.sourceUrls),
    contentEvidences: normalizeObjectArray(diagnosis.contentEvidences).map(normalizeContentEvidence),
    sections: normalizeObjectArray(diagnosis.sections).map(normalizeDiagnosisSection),
    nextActions: normalizeObjectArray(diagnosis.nextActions).map(normalizeNextAction),
    revisionDirectives: normalizeObjectArray(diagnosis.revisionDirectives).map(normalizeRevisionDirective),
  }
}

/**
 * 章节追溯既可能来自最新报告接口，也可能来自旧任务回放。
 * 这里统一把 bundle / fields / evidenceReferences 收成稳定数组，
 * 避免页面在默认主路径渲染“关键证据追溯”时被脏数据打断。
 */
export function normalizeSectionEvidenceBundles(bundles: unknown): SectionEvidenceBundleInfo[] {
  return normalizeObjectArray(bundles).map(normalizeSectionEvidenceBundle)
}

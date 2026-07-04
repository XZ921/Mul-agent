package cn.bugstack.competitoragent.search;

import cn.bugstack.competitoragent.agent.collector.CollectorNodeConfig;
import cn.bugstack.competitoragent.model.dto.SearchAuditSummary;
import cn.bugstack.competitoragent.search.tavily.FieldEvidenceQueryExecutionAudit;
import cn.bugstack.competitoragent.search.tavily.TavilyFastLaneAudit;
import cn.bugstack.competitoragent.source.SearchRequestPhase;
import cn.bugstack.competitoragent.source.SearchSourceRequest;
import cn.bugstack.competitoragent.source.SearchSourceProvider;
import cn.bugstack.competitoragent.source.SourceCandidate;
import cn.bugstack.competitoragent.source.SourceCandidateRanker;
import cn.bugstack.competitoragent.workflow.coverage.DimensionEvidencePlan;
import cn.bugstack.competitoragent.workflow.coverage.FieldEvidenceCoverage;
import cn.bugstack.competitoragent.workflow.coverage.FieldEvidenceQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 闂傚倸鍊搁崐椋庣矆娓氣偓楠炴牠顢曚綅閸ヮ剦鏁冮柨鏇楀亾闁汇倗鍋撶换婵囩節閸屾粌顣虹紓浣插亾濠㈣泛顑嗛崣蹇斾繆閻愰鍤欏ù婊堢畺濮婃椽妫冨☉娆樻缂備浇鍩栧畝鎼佹偘椤旈敮鍋撻敐搴℃灍闁哄懏绻堥弻宥堫檨闁告挻鐩崺鈧い鎺嶆祰婢规ɑ銇勯敂鐐毈鐎殿喖顭烽弫鎰緞婵犲喚妫熼梻浣告贡椤牊顨ラ幖浣告辈闁割偁鍎查埛鎴犵磼鐎ｎ偒鍎ラ柛搴㈠姉缁辨帞鎷犻幓鎺濅紑濠碘€冲级閸旀瑩鐛幒妤€绠婚柛鎾茬劍閸ゅ矂姊绘担绋款棌闁绘挸鐗撳畷鏉款潩椤撶儐鍤ら梺鍛婃处閸樺墽澹曢挊澶堚偓鎺戭潩閿濆懍澹曠紓浣鸿檸閸樺ジ骞夐敍鍕焿鐎广儱顦介弫鍌炴煕椤愮姴鐏╃紒渚婄畵濮婃椽宕烽鐐插濡炪們鍔岄幊鎰垝婵犳艾绠柤鎭掑劤閸樼敻鏌ｆ惔锝嗘毄妞ゎ厼鐗婄粋鎺曨槾闁瑰弶鎮傞幃褔宕奸姀銏㈠帨婵?
 * 闂傚倸鍊搁崐鎼佸磹妞嬪孩顐芥慨姗嗗墻閻掍粙鏌ゆ慨鎰偓鏍偓姘煼閺岋綁寮崒姘粯缂備讲鍋撳璺哄閸嬫捇鐛崹顔煎濡炪倧缂氱划娆忣嚕椤愶箑骞㈡俊顖濆亹閻﹀牊绻濋悽闈浶㈤柛濠冩倐瀵悂濡舵径瀣幈闂侀潧鐗嗗Λ娆戠矆鐎ｎ喗鐓熼柟鍨暙娴滄壆鈧娲栭悥鍏间繆濮濆矈妲峰┑鈩冨絻閻楀棝鍩為幋锔藉€烽柛娆忣槸濞呫倝鎮楅崗澶婁壕缂備礁顑嗛娆忣焽閺嵮€鏀介柣妯虹枃婢规ɑ顨ラ悙顏勭仾濞ｅ洤锕俊鍫曞礋椤擄紕鐛ユ繝纰樻閸嬪棝宕戦悙鍨床婵犻潧顑呴悡鏇㈡煃瑜滈崜鐔风暦閵娾晩鏁囧璺虹墕閸斻倗绱掓潏銊ョ瑲婵炵厧绻樻俊鎼佸Ψ閿曚胶妾ㄩ梻鍌欐祰椤曟牠宕伴幘璇插瀭闁芥ê顦遍弳锔界節婵犲倸顏柣鐔风秺閺屾盯濡烽姘兼喘缂備讲鍋撻柛鎰靛枟閳锋帒霉閿濆洨鎽傞柛銈嗙懄缁绘稓鈧數顭堥崢瀛橆殽閻愯韬鐐搭焽閹风娀骞撻幒鏂跨秮濠碉紕鍋戦崐鏍暜閹烘柡鍋撳鐓庡⒋鐎规洖缍婂畷鎺戔槈濞嗗繐浼庢繝纰樻閸ㄨ京鈧瑳鍛厹濡わ絽鍟悡鐔肩叓閸ャ劎顣查柣婵愪簽缁辨帞绱掑Ο鑲╃暫缂備胶绮换鍫濈暦閹烘垟妲堢€规洖娲ㄩˇ顖炴⒒閸屾瑧鍔嶉柟顔肩埣瀹曟劙骞嬮敃鈧崹鍌毭归悩宸剰闁藉啰鍠愮换娑㈠箣閻愬啯宀稿鍛婃償閵婏妇鍘甸梻渚囧弿缁犳垿寮稿☉銏＄厓妞ゅ繐瀚粔鐑樻叏婵犲啯銇濋柡灞芥嚇閹瑩鎳犵捄渚純濠电姭鎷冪仦鐣屼桓闂佸搫鐭夌紞渚€骞冮姀銈呯煑濠㈣泛顑囪ぐ瀣⒒娴ｈ櫣銆婇柡鍌欑窔瀹?-> 濠电姷鏁告慨鎾儉婢舵劕绾ч幖瀛樻尭娴滅偓淇婇妶鍕妽闁告瑥绻橀弻鐔虹磼閵忕姵鐏嶉梺绋垮椤ㄥ懘濡撮幒鎴僵闁挎繂鎳嶆竟鏇㈡⒒娴ｇ瓔鍤冮柛顭戝灦閹偤鏁冮埀顒勵敋閵夆晛绀嬫い鎾寸☉娴滈箖鏌ㄥ┑鍡樺櫧濞寸姵鐩弻锝夋晲閸偄娈梺瀹狀潐閸ㄥ潡宕洪妷鈺佸耿婵°倕鍟╅崫妤佺節閻㈤潧袥闁稿鎹囧娲敆閳ь剛绮旂€靛摜鐜绘俊銈勭劍閸欏繑淇婇悙棰濆殭濞存粓绠栧娲传閸曨剚鎷辩紓浣割儐閻楁粎鍒掔拠娴嬫闁靛繒濮烽鎺楁煟閻樿崵绱伴柕鍡忓亾闂佸憡鐟㈤埀顒佹灱閺€浠嬪箳閹惰棄纾规俊銈勭劍閸欏繘鏌℃径瀣婵炲樊浜堕弫鍥煟閹邦剛浠涙繛鍫ョ畺濮婇缚銇愰幒鎴滃枈闂佺绻戦敃銏狀嚕閸涘﹥鍎熼柕濠忓閸樼數绱撻崒娆撴闁搞劌缍婇幃姗€鎮╃憗?-> 濠电姷鏁告慨鐑藉极閹间礁纾婚柣鎰惈閸ㄥ倿鏌涢锝嗙缂佺姳鍗抽弻娑樷攽閸曨偄濮㈤梺娲诲幗閹瑰洭寮婚敐澶婄闁挎繂妫Λ鍕磽娴ｆ彃浜鹃梺鍛婂姂閸斿寮ㄦ禒瀣厽婵☆垵娅ｉ弸鍐熆鐟欏嫷鐒界紒杈ㄥ浮椤㈡洟濮€閳跺灕鍥ㄧ厵妞ゆ梹顑欏鎰箾绾板彉閭鐐茬箰閻ｆ繃绻濋崒姣笺倝姊婚崒娆戭槮闁圭⒈鍋婅棟妞ゆ牜鍋愰埀顒婄畵閹粓鎸婃径宀€鏆?-> 闂傚倸鍊搁崐鎼佸磹妞嬪海鐭嗗〒姘ｅ亾妤犵偞鐗犻、鏇㈡晜閽樺缃曢梻浣告啞閸旓箓宕伴弽顐㈩棜濠电姵纰嶉悡娆撴煙椤栧棗鍠氶弳銏㈢磼閻愵剙绀冩俊顐㈠濠€渚€姊洪幐搴ｇ畵闁瑰啿绻橀獮澶岀矙濞嗗墽鍞甸悷婊勭箘缁骞樼拠鑼舵憰閻庡箍鍎遍悧婊冾瀶閵娾晜鈷戦柛娑橈攻鐏忎即鏌ｉ悢鍙夋珗婵☆偆鍠栧铏规喆閸曢潧鏅遍梺鍝ュУ缁嬫帡骞堥妸鈺傛優妞ゆ劗濮崇花濠氭⒑閸濆嫭澶勬い銊ユ噺缁傚秵銈ｉ崘鈹炬嫼闂佺鍋愰崑娑欎繆娴犲鐓曢幖娣灩閳绘洟鏌涢埡鍐ㄤ槐妤犵偛顑夐弫鍐焵椤掑倻鐭嗛悗锝庡枟閻撳繐鈹戦悙鑼虎闁告梹鐟ラ…?
 */
@Component
public class SearchExecutionCoordinator {

    private static final Logger log = LoggerFactory.getLogger(SearchExecutionCoordinator.class);
    private static final String EXPLICIT_URL_CANONICALIZE_FAILED = "EXPLICIT_URL_CANONICALIZE_FAILED";
    private static final String EXPLICIT_URL_DUPLICATE_CANONICAL = "EXPLICIT_URL_DUPLICATE_CANONICAL";

    private final CandidateVerifier candidateVerifier;
    private final BrowserSearchRuntimeService browserSearchRuntimeService;
    private final SearchSourceProvider searchSourceProvider;
    private final SourceCandidateRanker sourceCandidateRanker;
    private final CollectionTargetSelector collectionTargetSelector;
    private final SearchPolicyResolver searchPolicyResolver;
    private final CanonicalUrlResolver canonicalUrlResolver;
    private final SourceFamilyDirectDiscoveryPlanner directDiscoveryPlanner;
    private final SitemapDiscoveryService sitemapDiscoveryService;
    private final CandidateOwnershipPolicy candidateOwnershipPolicy;
    private final TavilyBootstrapPlanner tavilyBootstrapPlanner;
    private final SearchCandidateFusionPlanner searchCandidateFusionPlanner;
    private final PublicEvidenceRecoveryService publicEvidenceRecoveryService;
    /**
     * 字段证据执行闸门是纯规则组件，直接在 coordinator 内复用即可。
     * 这里显式收口 planned -> executable 的数量治理，避免 provider 再收到膨胀后的全量 query。
     */
    private final FieldEvidenceQueryExecutionGate fieldEvidenceQueryExecutionGate = new FieldEvidenceQueryExecutionGate();

    public SearchExecutionCoordinator(CandidateVerifier candidateVerifier,
                                      BrowserSearchRuntimeService browserSearchRuntimeService,
                                      SearchSourceProvider searchSourceProvider,
                                      SourceCandidateRanker sourceCandidateRanker,
                                      CollectionTargetSelector collectionTargetSelector,
                                      SearchPolicyResolver searchPolicyResolver) {
        this(candidateVerifier,
                browserSearchRuntimeService,
                searchSourceProvider,
                sourceCandidateRanker,
                collectionTargetSelector,
                searchPolicyResolver,
                new CanonicalUrlResolver(),
                new SitemapDiscoveryService(new SitemapDiscoveryProperties()),
                new CandidateOwnershipPolicy(),
                new TavilyBootstrapPlanner(),
                new PublicEvidenceRecoveryService());
    }

    @Autowired
    public SearchExecutionCoordinator(CandidateVerifier candidateVerifier,
                                      BrowserSearchRuntimeService browserSearchRuntimeService,
                                      SearchSourceProvider searchSourceProvider,
                                      SourceCandidateRanker sourceCandidateRanker,
                                      CollectionTargetSelector collectionTargetSelector,
                                      SearchPolicyResolver searchPolicyResolver,
                                      CanonicalUrlResolver canonicalUrlResolver,
                                      SitemapDiscoveryService sitemapDiscoveryService) {
        this(candidateVerifier,
                browserSearchRuntimeService,
                searchSourceProvider,
                sourceCandidateRanker,
                collectionTargetSelector,
                searchPolicyResolver,
                canonicalUrlResolver,
                sitemapDiscoveryService,
                new CandidateOwnershipPolicy(),
                new TavilyBootstrapPlanner(),
                new PublicEvidenceRecoveryService());
    }

    public SearchExecutionCoordinator(CandidateVerifier candidateVerifier,
                                      BrowserSearchRuntimeService browserSearchRuntimeService,
                                      SearchSourceProvider searchSourceProvider,
                                      SourceCandidateRanker sourceCandidateRanker,
                                      CollectionTargetSelector collectionTargetSelector,
                                      SearchPolicyResolver searchPolicyResolver,
                                      CanonicalUrlResolver canonicalUrlResolver,
                                      SitemapDiscoveryService sitemapDiscoveryService,
                                      CandidateOwnershipPolicy candidateOwnershipPolicy) {
        this(candidateVerifier,
                browserSearchRuntimeService,
                searchSourceProvider,
                sourceCandidateRanker,
                collectionTargetSelector,
                searchPolicyResolver,
                canonicalUrlResolver,
                sitemapDiscoveryService,
                candidateOwnershipPolicy,
                new TavilyBootstrapPlanner(),
                new PublicEvidenceRecoveryService());
    }

    public SearchExecutionCoordinator(CandidateVerifier candidateVerifier,
                                      BrowserSearchRuntimeService browserSearchRuntimeService,
                                      SearchSourceProvider searchSourceProvider,
                                      SourceCandidateRanker sourceCandidateRanker,
                                      CollectionTargetSelector collectionTargetSelector,
                                      SearchPolicyResolver searchPolicyResolver,
                                      CanonicalUrlResolver canonicalUrlResolver,
                                      SitemapDiscoveryService sitemapDiscoveryService,
                                      CandidateOwnershipPolicy candidateOwnershipPolicy,
                                      TavilyBootstrapPlanner tavilyBootstrapPlanner) {
        this(candidateVerifier,
                browserSearchRuntimeService,
                searchSourceProvider,
                sourceCandidateRanker,
                collectionTargetSelector,
                searchPolicyResolver,
                canonicalUrlResolver,
                sitemapDiscoveryService,
                candidateOwnershipPolicy,
                tavilyBootstrapPlanner,
                new PublicEvidenceRecoveryService());
    }

    public SearchExecutionCoordinator(CandidateVerifier candidateVerifier,
                                      BrowserSearchRuntimeService browserSearchRuntimeService,
                                      SearchSourceProvider searchSourceProvider,
                                      SourceCandidateRanker sourceCandidateRanker,
                                      CollectionTargetSelector collectionTargetSelector,
                                      SearchPolicyResolver searchPolicyResolver,
                                      CanonicalUrlResolver canonicalUrlResolver,
                                      SitemapDiscoveryService sitemapDiscoveryService,
                                      CandidateOwnershipPolicy candidateOwnershipPolicy,
                                      TavilyBootstrapPlanner tavilyBootstrapPlanner,
                                      PublicEvidenceRecoveryService publicEvidenceRecoveryService) {
        this.candidateVerifier = candidateVerifier;
        this.browserSearchRuntimeService = browserSearchRuntimeService;
        this.searchSourceProvider = searchSourceProvider;
        this.sourceCandidateRanker = sourceCandidateRanker;
        this.collectionTargetSelector = collectionTargetSelector;
        this.searchPolicyResolver = searchPolicyResolver;
        this.canonicalUrlResolver = canonicalUrlResolver;
        this.directDiscoveryPlanner = new SourceFamilyDirectDiscoveryPlanner(this.searchPolicyResolver);
        this.sitemapDiscoveryService = sitemapDiscoveryService == null
                ? new SitemapDiscoveryService(new SitemapDiscoveryProperties())
                : sitemapDiscoveryService;
        this.candidateOwnershipPolicy = candidateOwnershipPolicy == null
                ? new CandidateOwnershipPolicy()
                : candidateOwnershipPolicy;
        this.tavilyBootstrapPlanner = tavilyBootstrapPlanner == null
                ? new TavilyBootstrapPlanner(this.searchPolicyResolver)
                : tavilyBootstrapPlanner;
        this.searchCandidateFusionPlanner = new SearchCandidateFusionPlanner(this.searchPolicyResolver, this.sourceCandidateRanker);
        this.publicEvidenceRecoveryService = publicEvidenceRecoveryService == null
                ? new PublicEvidenceRecoveryService()
                : publicEvidenceRecoveryService;
    }

    public SearchExecutionResult execute(CollectorNodeConfig config) {
        return execute(config, null);
    }


    /**
     * 闂?repair 闂傚倸鍊搁崐鎼佸磹閻戣姤鍊块柨鏇炲€归崕鎴犳喐閻楀牆绗掗柛銊ュ€搁埞鎴︽偐鐎圭姴顥濈紓浣瑰姈椤ㄥ﹪鐛弽顬ュ酣顢楅埀顒佷繆閼测晝纾奸柣姗€娼ч埢鍫ユ煛鐏炶濮傞柟顔哄灲瀹曨偊宕熼幋娆忕伄闁逞屽墰閹虫捇骞夐敍鍕床闁割偁鍎遍拑鐔兼煛閸モ晛鏋旂紒鈾€鍋撻梻濠庡亜濞诧妇绮欓幒妤婃晛鐎广儱顦伴埛鎴犵磼椤栨稒绀冩繛鍛嚇閺屾稒绻濋崒娑樹淮閻庢鍠栭…鐑藉箖閵忋倕绀傞柣鎾冲閻ゅ倿姊绘担绋款棌闁绘挸鐗撳畷鎴﹀幢濞存澘娲俊鐑芥晜閸撗呮闂傚倸鍊搁悧濠勭矙閹达箑鐒垫い鎺嗗亾缂傚秴锕獮鍐槻閾绘牠鏌涢幇鍏哥敖闁绘挻鎸荤换婵嬪閿濆懐鍘梺鍛婃⒐閻楃娀鐛崱娑欏€烽柛娆忓€瑰Λ鍐箖閳哄拋鏁婇柣鎾冲瘨閻庡瓨绻濆▓鍨灈闁挎洏鍊濋垾锕傛倻閽樺妲梺閫炲苯澧柕鍥у楠炴帡骞嬪┑鍥╀壕婵犵數鍋涢崥瀣礉濞嗘挸钃熼柕鍫濈墑娴滃綊鏌熼悜妯烩拻闁哄棭鍋勯—鍐Χ閸愩劎浠鹃梺鎸庢磸閸ㄥ綊鎮鹃悜绛嬫晝闁挎洍鍋撻崬顖炴⒑閹稿孩纾甸柛瀣尰閵囧嫭鎯旈姀銏″垱濠殿喖锕ュ浠嬪箖濞嗘挻瀵犲璺猴工椤挾绱撴担绋库挃闁惧繐閰ｅ畷锝夊礃椤旀儳绁﹀┑掳鍊曢幊蹇涘磻閸曨垱鐓曟繝闈涘閸斻倖銇勯弮鈧ú鐔奉潖濞差亜绀堥柟缁樺笂缁ㄦ挳姊洪幖鐐插妞ゎ偄顦辩划瀣箳濡ゅ﹥鏅梺缁樺姇椤曨參宕?     * 闂傚倸鍊搁崐椋庣矆娓氣偓楠炴牠顢曚綅閸ヮ剦鏁冮柨鏇楀亾闁汇倗鍋撶换婵囩節閸屾粌顤€闂佺顑戠换婵嬪蓟瀹ュ浼犻柛鏇ㄥ墮濞咃綁姊婚崒姘簽闁搞劏娉涢～蹇撁洪鍕€銈嗘礀閹冲酣宕滈幎鑺モ拺鐎规洖娲﹂崵鈧紓浣割槺閺佹悂骞戦姀鐘斀閻庯綆鍋掑Λ鍐ㄢ攽閻愭潙鐏﹂柣鐕傜畵楠炲啫鈻庨幘绮规嫼婵炴潙鍚嬮悷銉╂儊濠婂牊鐓曢柣妯挎珪閻ㄦ垶淇婇崣澶婂妤犵偞甯″顕€宕掑鎰簥闂傚倷绶氬褏鎹㈤幒鎾村弿闂佸灝顑囬々鐑芥煃閸濆嫭鍣洪柍閿嬪灴閺岀喖鎳栭埡浣风捕闂侀€炲苯澧紒璇插暟缁顓奸崶銊ョ／婵炴挻鍑归崹鏉库枔閵娿儺娓婚柕鍫濇婵倿鏌涙繝鍐⒌鐎殿喖鍟块…銊╁礋閳衡偓缁ㄥ姊洪崫鍕偓鎼佹倶濠靛鐓曢柡鍐ｅ亾闁靛洤瀚伴弫鍌滄嫚閸欏浜剁紓鍌欒兌缁垶鎯勯鐐靛祦閻庯綆鍠楅崐鐑芥煛婢跺鐏╂い锝堝皺缁辨捇宕掑▎鎴М濡炪倧绠撴禍璺侯嚕缂佹ê绶為柟顖滄暩閸忔ê鐣烽幒妤佸€风紒顔款潐鐎?Tavily/婵犵數濮烽弫鍛婃叏閻戣棄鏋侀柟闂寸绾惧鏌ｉ幇顒佲枙闁绘帟濮ょ换娑㈠幢濡櫣浠搁悗瑙勬礀瀵埖绌辨繝鍥ч柛娑卞枛閻濇梻绱撻崒姘毙ｉ柣妤佺矒閸┾偓妞ゆ巻鍋撶紒鐘茬Ч瀹曟洟鏌嗗畵銉ユ喘椤㈡盯鎮欑划瑙勫?濠电姷鏁告慨鎾儉婢舵劕绾ч幖瀛樻尭娴滅偓淇婇妶鍕妽闁告瑥绻橀弻鐔虹磼閵忕姵鐏嶉梺绋垮椤ㄥ懘濡撮幒鎴僵闁挎繂鎳嶆竟鏇㈡⒒娴ｇ瓔鍤冮柛顭戝灦閹偤鏁冮埀顒勵敋閵夆晛绀嬫い鏍ㄦ皑閻も偓婵＄偑鍊栫敮鎺斺偓姘煎弮閺佸秴螖娴ｉ绠氶梺缁樺姦娴滄粓鍩€椤戞儳鈧繂鐣烽姀銈嗗仼閻忕偟鍋撳▓浼存⒑缂佹ê濮夐柡浣规倐閹矂宕卞Δ濠勫數闁荤娀缂氬▍锝夋倶鏉堛劋绻嗘い鎰靛亜閻忥繝鏌曢崶褍顏鐐疵灃闁逞屽墴瀹曨垶顢涢悙鏉戔偓鍫曟煕閳╁啰鎳呯痪?replay 闂傚倸鍊搁崐鎼佸磹妞嬪海鐭嗗〒姘ｅ亾妤犵偛顦甸弫宥夊礋椤愩垻浜伴柣搴″帨閸嬫捇鏌涢弴銊ュ婵炲牞绲介—鍐Χ閸℃瑥顫у┑顔角滈崝鎴濐嚕閹惰姤鏅濋柛灞剧〒閸樹粙姊洪崫鍕殭闁稿﹤鎽滈弫顕€宕滄担铏癸紲闂侀€炲苯澧寸€规洘甯￠幃娆戔偓鐢殿焾楠炲牓姊绘担渚敯闁糕晛娲畷婵嗩吋閸涱亝鐎洪梺鎼炲労閸撴岸鎮¤箛娑欑厱妞ゆ劑鍊曢弸鎴︽倶韫囥儳鐣甸柡宀€鍠栭、娆撴嚃閳哄唭銊╂倵鐟欏嫭绀€闁绘牕鍚嬫穱濠囧箹娴ｈ娅嗙紓鍌欓檷閸ㄩ缚鈪查梻鍌欐祰瀹曠敻宕伴幇顔煎灊鐎广儱鎷嬮悢鍡樹繆椤栨瑨顒熼柛銈嗘礋閺屾盯骞橀懠璺哄帯闂佹椿鍘奸ˇ鐢稿蓟閻斿吋鍊绘慨妤€妫欓悾鍫曟煕閻戝棗鐏﹂柟顔煎槻椤劑宕橀悙顑芥瀰闂備胶纭堕弬渚€宕戦幘鎰佹富闁靛牆妫楃粭鍌滅磼鐠佸湱绡€闁诡喚鍋炵粋鎺斺偓锝庡亞閸樻捇鎮峰鍕煉鐎规洘绮岄埞鎴犫偓锝呭缁嬪繑绻濋姀锝嗙【闁兼椿鍨跺鎼佸醇閳垛晛浜炬鐐茬仢閸旀碍銇勯敂璇茬仸闁诡喚鍋ゅ畷褰掝敃閻樿京鐩庨梻浣筋潐閹矂宕㈤崗鍏兼珷妞ゆ洍鍋撻柡灞剧洴閹晝鈧湱濮撮ˉ婵嬫煕濡も偓瀹曨剟鍩為幋锔藉亹鐎规洖娴傞弳锟犳⒑缂佹ɑ灏甸柛鐘冲姍婵＄敻宕熼锝嗘櫇濡炪倖鍔戦崹褰掞綖閳哄倻绡€缁剧増菤閸嬫捇鎮欓挊澶夊垝闂備礁鎼惉濂稿窗閺嶎厹鈧礁鈻庨幘鏉戞疅闂侀潧顦崕鐢告倵閾忓湱纾介柛灞剧懅椤︼附銇勯幋婵囧殗閽樻繈鏌ｉ姀銏╃劸闁哄嫨鍎甸弻宥堫檨闁告挻宀告俊鐢稿礋椤栨氨顔婇悗骞垮劚閻楀棝宕㈤敓鐘斥拻闁稿本鑹鹃鈺呮倵濮樼厧骞楃紒宀冮哺缁绘繈宕堕‖顑洦鐓曟繛鎴濆船楠炴绻涢崼顐㈠箻缂佽鲸甯掕灃濞达絽鎽滄导灞解攽椤旂》榫氭繛鍜冪秮楠炲繘鎮╃拠鑼紜闂佸憡鍔栬ぐ鍐不閻楀牏绡€闁汇垽娼ф禒锔界箾閸忚偐鎳冮悡銈嗐亜閹惧崬鐏╃紒鐘宠壘闇夐柨婵嗘川閵嗗﹪鏌￠埀顒佺鐎ｎ偆鍘遍梺缁樏崯鎸庡劔闂備焦鎮堕崕婊堝川椤撴稑浜鹃柣鎰劋閳锋垿姊婚崼鐔剁繁婵℃彃鐖奸弻娑㈠Ω椤垶缍堢紓?     */
    static Map<String, Object> buildRepairAuditProjection(EvidenceRepairPlan repairPlan) {
        if (repairPlan == null) {
            return Map.of(
                    "repairState", EvidenceRepairState.REPAIR_NOT_REQUIRED.name(),
                    "repairQueries", List.of(),
                    "candidateUrls", List.of(),
                    "promotedUrls", List.of()
            );
        }
        return Map.of(
                "repairState", repairPlan.getState() == null
                        ? EvidenceRepairState.REPAIR_NOT_REQUIRED.name()
                        : repairPlan.getState().name(),
                "repairReason", repairPlan.getReason() == null ? "" : repairPlan.getReason(),
                "sourceUrl", repairPlan.getSourceUrl() == null ? "" : repairPlan.getSourceUrl(),
                "repairQueries", repairPlan.getRepairQueries() == null ? List.of() : repairPlan.getRepairQueries(),
                "candidateUrls", repairPlan.getCandidateUrls() == null ? List.of() : repairPlan.getCandidateUrls(),
                "promotedUrls", repairPlan.getPromotedUrls() == null ? List.of() : repairPlan.getPromotedUrls()
        );
    }

    public SearchExecutionResult execute(CollectorNodeConfig config,
                                         Consumer<SearchExecutionUpdate> progressListener) {
        long searchStartedAt = System.currentTimeMillis();
        SearchExecutionPlan executionPlan = initializePlan(config.getSearchExecutionPlan());
        long baseSearchTimeoutMillis = searchPolicyResolver.resolveSearchTimeoutMillis(
                config.getSearchTimeoutMillis(),
                executionPlan
        );
        ResolvedFieldEvidenceQueryPlan fieldEvidenceQueryPlan = resolveExecutableFieldEvidenceQueries(
                config,
                baseSearchTimeoutMillis
        );
        long searchTimeoutMillis = baseSearchTimeoutMillis;
        searchTimeoutMillis = searchPolicyResolver.ensureMinimumTimeoutForExecutableFieldEvidenceQueries(
                searchTimeoutMillis,
                fieldEvidenceQueryPlan.getExecutable()
        );
        Long fieldEvidenceExecutionDeadlineEpochMillis = resolveFieldEvidenceExecutionDeadlineEpochMillis(
                searchTimeoutMillis,
                fieldEvidenceQueryPlan
        );
        List<SearchProgressSnapshot> progressSnapshots = new ArrayList<>();
        Map<String, SearchCollectionTarget> attemptedTargets = new LinkedHashMap<>();
        SearchAuditSnapshot checkpoint = config.getSearchAuditCheckpoint();
        boolean resumedFromCheckpoint = checkpoint != null && checkpoint.getExecutionTrace() != null;
        // 濠电姷鏁告慨鐑藉极閸涘﹥鍙忛柣鎴濐潟閳ь剙鍊圭粋鎺斺偓锝庝簽閸旓箑顪冮妶鍡楀潑闁稿鎹囬弻娑㈡偄闁垮浠撮梺绯曟杹閸嬫挸顪冮妶鍡楀潑闁稿鎸剧槐鎾愁吋閸滃啳鍚Δ鐘靛仜閸燁偉鐏掗柣鐘叉穿鐏忔瑧绮ｉ悙鐑樷拺鐟滅増甯掓禍浼存煕閹惧鎳囬柟顕€娼ц灒闁煎鍊楅惁鍫ユ⒑闂堟盯鐛滅紒鎻掑⒔濞戠敻鎮欓鍙ョ盎闂佺懓顕崑娑欐叏閸ャ劊浜滄い鎰╁灮缁犳煡鏌曢崼顒傜М鐎规洘锕㈤崺锟犲礃閵娿儳顔囬梻鍌欐祰椤曆呮崲閹烘纾婚柣妯绘た閺佸鎲搁弬璺ㄦ殾婵犻潧顑呭洿婵犮垼娉涢鍥储闁秵鐓熼幖鎼灣閸掓澘顭胯濞撮鍒掗弮鍫晪闁逞屽墴瀵鍩勯崘鈺侇€撻梺鍛婄缚閸庢盯濡堕崥銈呯秺閹亪宕ㄩ婊勬闂備胶顢婂▍鏇㈡偋閻樿鏄ラ柨鐔哄Т缁€鍐┿亜韫囨挸顏╃紒鐘电帛缁绘繈鎮介棃娴躲儲銇勯敐鍫燁棄閸楅亶鏌涘☉娆愮稇缁炬儳顭烽弻宥堫檨闁告挾鍠栧璇测槈濮橈絽浜鹃柨婵嗛娴滄繄鈧娲栭惌鍌炲蓟閳ュ磭鏆嗛悗锝庡墰閿涚喐绻涚€电顎撳┑鈥虫喘楠炲繘鎮╃拠鑼唽闂佸湱鍎ら崵鈺呭箣閿旇В鎷绘繛杈剧导鐠€锕傛倿閻愵兙浜滈柟瀵稿仜閻忊晝绱掓潏鈺佷沪闁瑰嘲鎳樺畷顐﹀Ψ閿旂瓔鍟庡┑鐘垫暩婵炩偓婵炰匠鍥舵晞闁糕剝绋戠壕濠氭煙閹殿喖顣奸柣鎾跺枛閺岀喓鈧數顭堥崜閬嶆煟閹哄秶鐭欓柡灞炬礋瀹曠厧顭ㄩ崘銊с偖婵犳鍠栭敃銉ヮ渻娴犲宓侀柟閭﹀幗閸庣喖鏌ㄥ┑鍡椻偓鎼侊綖瀹€鈧槐鎾诲磼濞嗘埈妲銈嗗灥濡繂鐣疯ぐ鎺戦敜婵°倕鍟粊锕傛⒑缁洖澧叉い顓炴喘閹柉銇愰幒鎾跺帗閻熸粍绮撳畷婊冣枎閹炬潙浠奸悗鐟板閸嬪﹤顭囬埡鍌樹簻闁硅揪绲借闂佽楠搁…宄邦潖閾忚鍏滈柛婊€绀佸▓鎰版⒑缁嬫鍎忛柟铏姍瀵偊顢氶埀顒勫垂妤ｅ啫绠涘ù锝呮啞椤撳潡姊绘担鍝ョШ婵☆偉娉曠划鍫熸媴閾忛€涚瑝濠电偞鍨堕悷锝嗙濠婂牊鐓欓柛婵嗗椤ユ粌霉濠婂牏鐣烘慨濠呮閹风娀鎳犻鍌滄澖闂備胶顭堥柊锝嗙閸洖鏄ラ柣鎰惈缁狅綁鏌ㄩ弮鍌滃笡闁哄應鏅濈槐鎺楁倷椤掍胶鍑￠悗瑙勬处閸撶喎鐣峰鍫濋唶闁哄洨鍟块幏娲⒑閸涘﹦鈽夋い顒冩椤劑宕煎┑鍫串婵犲痉鏉库偓鏇㈠疮椤栫儑缍?
        String checkpointSource = resumedFromCheckpoint ? "NODE_CONFIG_CHECKPOINT" : null;
        InitialCandidateResolution initialCandidateResolution = resumedFromCheckpoint
                ? new InitialCandidateResolution(resolveCandidatesFromCheckpoint(checkpoint), List.of())
                : resolveInitialCandidatesWithDiagnostics(config);
        List<SourceCandidate> explicitUrlRejectedCandidates = initialCandidateResolution.rejectedCandidates();
        List<SourceCandidate> allCandidates = normalizeCandidates(
                initialCandidateResolution.candidates(),
                "PLANNED",
                config
        );
        // ====================== 闂? 缂傚倸鍊搁崐鎼佸磹閹间礁纾归柟闂寸绾惧綊鏌ｉ幋锝呅撻柛濠傛健閺屻劑寮撮悙娴嬪亾閸濄儳涓嶅ù鐓庣摠閸嬶綁鏌涢妷鎴濆閺嬫瑩姊?缂傚倸鍊搁崐鎼佸磹閹间礁纾归柣鎴ｅГ閸ゅ嫰鏌涢锝嗙闁诡垳鍋ら獮鏍庨鈧俊濂告煕閵娿儱鈧潡寮婚敐鍛傛棃鍩€椤掑嫭鍋嬪┑鐘插€甸弸宥夋煟濡偐甯涢柣鎾跺枑娣囧﹪顢涘顒佸€梺鍝勬閺呮繈鎯€椤忓牆绀堢憸宥嗙妤ｅ啯鐓忛柛銉戝喚浼冮悗娈垮櫘閸ｏ絽鐣烽幒鎴僵妞ゆ垼娉曠敮娑樷攽閻樺灚鏆╅柛瀣洴楠炲﹨绠涘☉娆忎画闂侀潧顦弬渚€鍩€椤掑﹤顩柟鐟板婵℃悂鏁冮埀顒傚椤栫偞鈷戠紓浣姑悘锔姐亜椤撶偞鍠樺┑鈥崇埣椤㈡洟濡堕崶鈺嬬闯濠电偠鎻徊鑲╁垝濞嗘挸浼犻柧蹇撴贡绾惧ジ鎮归崶銊ョ祷闁哄棛鍠撶槐鎺楊敊閻ｅ本鍣悗鍨緲鐎氼厾鎹㈠┑鍥ㄥ劅闁斥晛鍟埀顒夊灦濮婄粯鎷呴搹骞库偓濠囨煕閹惧绠炲┑锟犳涧閳藉濮€閻樻鍞堕梻浣告啞閸旓箓宕板Δ鍛亗闁绘柨鍚嬮悡鐔兼煙闁箑澧婚柛銈囧枛閺屽秹鎸婃径妯恍﹂梺瀹狀潐閸ㄥ灝鐣烽幒鎴旀婵炲棗鏈€氱晫绱撻崒娆戣窗闁革綆鍣ｅ畷锝夊礃椤旇偐鐣洪梺闈涚箞閸婃牠宕戦崒鐐茬閺夊牆澧界粔鍨繆閼碱剙鍘存慨濠勭帛閹峰懘鎳為妷褋鈧﹪姊洪崫銉バｉ柟绋款煼楠炲牓濡搁埡濠冩櫍闂侀潧绻掓慨鐑芥晬濞戙垺鈷戦悷娆忓缁€鍐┿亜閺囧棗娲ら惌妤€鈹戦悩鍙夊窛闁告瑦鎹囬弻娑㈠Ψ閿濆懎顬夌紓浣插亾闁逞屽墰缁?=====================
        if (resumedFromCheckpoint) {
            attemptedTargets.putAll(resolveAttemptedTargetsFromCheckpoint(checkpoint));
        }
        int targetCount = searchPolicyResolver.resolveTargetCount(
                config.getMaxSearchResults(),
                config.getCompetitorUrls(),
                allCandidates.size()
        );
        int plannedUrlCount = config.getCompetitorUrls() == null ? 0 : config.getCompetitorUrls().size();
        int minVerifiedCount = searchPolicyResolver.resolveMinVerifiedCandidates(
                config.getMinVerifiedCandidates(),
                plannedUrlCount,
                targetCount
        );
        SearchRuntimePolicy runtimePolicy = resolveRuntimePolicy(config);
        int bootstrapCandidateLimit = searchPolicyResolver.resolveBootstrapCandidateLimit(runtimePolicy, targetCount);
        int supplementCandidateLimit = searchPolicyResolver.resolveSupplementCandidateLimit(runtimePolicy, targetCount);
        int maxCandidatePoolSize = searchPolicyResolver.resolveMaxCandidatePoolSize(runtimePolicy, targetCount);
        int maxCandidatesPerDomain = searchPolicyResolver.resolveMaxCandidatesPerDomain(runtimePolicy);
        executionPlan = enrichExecutionPlan(executionPlan, config, targetCount, minVerifiedCount);
        boolean circuitBroken = false;
        String degradationReason = null;

        markStepRunning(executionPlan, "LOAD_CANDIDATES", "loading planned candidates");
        appendSnapshotAndPublish(progressSnapshots, executionPlan, "LOAD_CANDIDATES",
                "loading planned candidates", false, null, progressListener, allCandidates, List.of(), null);
        String loadCandidatesMessage = buildLoadCandidatesSummary(
                resumedFromCheckpoint,
                allCandidates.size(),
                explicitUrlRejectedCandidates
        );
        markStepSuccess(executionPlan, "LOAD_CANDIDATES", loadCandidatesMessage);
        appendSnapshotAndPublish(progressSnapshots, executionPlan, "LOAD_CANDIDATES",
                loadCandidatesMessage,
                false, null, progressListener, allCandidates, List.of(), null);

        TavilyBootstrapDecision bootstrapDecision = tavilyBootstrapPlanner.plan(config, allCandidates);
        if (bootstrapDecision.isShouldExecute()) {
            markStepRunning(executionPlan, "TAVILY_BOOTSTRAP_ENRICH", bootstrapDecision.getReason());
            appendSnapshotAndPublish(progressSnapshots, executionPlan, "TAVILY_BOOTSTRAP_ENRICH",
                    bootstrapDecision.getReason(), false, null, progressListener, allCandidates, List.of(), null);
            try {
                List<SourceCandidate> bootstrapCandidates = normalizeCandidates(
                        searchSourceProvider.search(bootstrapDecision.getRequest()),
                        "BOOTSTRAPPED",
                        config
                );
                bootstrapCandidates = sourceCandidateRanker.rankDeduplicateAndLimit(
                        bootstrapCandidates,
                        bootstrapCandidateLimit,
                        maxCandidatesPerDomain
                );
                allCandidates = sourceCandidateRanker.rankDeduplicateAndLimit(
                        concat(allCandidates, bootstrapCandidates),
                        maxCandidatePoolSize,
                        maxCandidatesPerDomain
                );
                String bootstrapMessage = bootstrapCandidates.isEmpty()
                        ? "Tavily Phase 1 bootstrap returned no new candidates"
                        : "Tavily Phase 1 bootstrap added " + bootstrapCandidates.size() + " candidates";
                markStepSuccess(executionPlan, "TAVILY_BOOTSTRAP_ENRICH", bootstrapMessage);
                appendSnapshotAndPublish(progressSnapshots, executionPlan, "TAVILY_BOOTSTRAP_ENRICH",
                        bootstrapMessage, false, null, progressListener, allCandidates, List.of(), null);
            } catch (RuntimeException exception) {
                String failOpenMessage = "Tavily Phase 1 bootstrap failed open; keep planned candidates";
                markStepSuccess(executionPlan, "TAVILY_BOOTSTRAP_ENRICH", failOpenMessage);
                appendSnapshotAndPublish(progressSnapshots, executionPlan, "TAVILY_BOOTSTRAP_ENRICH",
                        failOpenMessage, false, null, progressListener, allCandidates, List.of(), null);
            }
        } else {
            markStepSkipped(executionPlan, "TAVILY_BOOTSTRAP_ENRICH", bootstrapDecision.getReason());
            appendSnapshotAndPublish(progressSnapshots, executionPlan, "TAVILY_BOOTSTRAP_ENRICH",
                    bootstrapDecision.getReason(), false, null, progressListener, allCandidates, List.of(), null);
        }

        markStepRunning(executionPlan, "CANDIDATE_FUSION_RANK", "running final candidate fusion and ranking");
        appendSnapshotAndPublish(progressSnapshots, executionPlan, "CANDIDATE_FUSION_RANK",
                "running final candidate fusion and ranking", false, null, progressListener, allCandidates, List.of(), null);
        SearchCandidateFusionDecision initialFusionDecision = searchCandidateFusionPlanner.plan(
                config,
                allCandidates,
                targetCount,
                maxCandidatesPerDomain
        );
        if (initialFusionDecision.getRankedCandidates() != null && !initialFusionDecision.getRankedCandidates().isEmpty()) {
            allCandidates = initialFusionDecision.getRankedCandidates();
        }
        int effectiveTargetCount = initialFusionDecision.getEffectiveTargetCount();
        markStepSuccess(executionPlan, "CANDIDATE_FUSION_RANK", initialFusionDecision.getReason());
        appendSnapshotAndPublish(progressSnapshots, executionPlan, "CANDIDATE_FUSION_RANK",
                initialFusionDecision.getReason(), false, null, progressListener, allCandidates, List.of(), null);

        int verifiedCount = 0;
        int supplementedCount = 0;
        boolean candidatePoolChangedAfterInitialFusion = false;
        boolean publicEvidenceRecoveryTriggered = false;
        String publicEvidenceRecoveryStatus = "RECOVERY_NOT_TRIGGERED";
        List<String> publicEvidenceAttemptedUrls = List.of();
        List<String> publicEvidenceAttemptedEvidencePaths = List.of();
        List<String> publicEvidenceRecoveryQueryIntents = List.of();
        int publicEvidenceRecoveryCandidateCount = 0;
        int publicEvidenceRecoveryVerifiedCount = 0;
        Map<String, Object> evidenceRepairPlanProjection = buildRepairAuditProjection(null);
        VerificationStatsAggregate verificationStats = new VerificationStatsAggregate();
        boolean resultPageVerificationEnabled = isResultPageVerificationEnabled(config);
        String supplementMethod = "NONE";
        boolean providerFallbackUsed = false;
        String fallbackDecision = "USE_PLANNED_CANDIDATES";
        BrowserSearchRuntimeResult browserSearchResult = BrowserSearchRuntimeResult.builder()
                .candidates(List.of())
                .executedQueries(List.of())
                .summary("planned candidates only")
                .fallbackSuggested(false)
                .blockedCount(0)
                .build();
        TavilyFastLaneAudit providerTavilyFastLaneAudit = null;

        // 闂傚倸鍊搁崐鎼佸磹閻戣姤鍤勯柛顐ｆ礀閸屻劎鎲稿澶嬪仼闁绘垹鐡旈弫鍡涙煕閺囥劌澧柛鎾诡潐缁绘盯骞橀弶鎴犲姲闂佺顑嗛幑鍥蓟閻旂⒈鏁嶆慨妯哄暱椤忣參鏌х紒妯煎ⅹ闂囧鏌ㄥ┑鍡樺櫤閻犳劧绱曠槐鎺撳緞婵犲嫬鐓熷┑顔硷攻濡炶棄螞閸愵喖鐓涘ù锝囧劋琚欓梻鍌欑閹诧繝鈥﹂崼婢盯宕熼姘卞幒?1闂傚倸鍊搁崐鎼佸磹閻戣姤鍊块柨鏃堟暜閸嬫挾绮☉妯诲櫧闁活厽鐟╅弻鐔兼倻濮楀棙鐣烽梺鎼炲€曢懟顖濈亙闂佹寧绻傞幊搴ㄥ汲濞嗘挻鐓曢悘鐐额嚙婵＄晫绱掔紒妯兼创妤犵偛顑夐幃娆撳垂椤愩垺顏ゅ┑鐘垫暩閸嬫盯鎮ц箛娑樼柧妞ゆ劧绠戦悞鍨亜閹哄棗浜鹃梺鍛娚戠划鎾崇暦閹达箑绠荤紓浣诡焽閸橀亶姊虹憴鍕凡闁告埃鍋撶紓浣靛妼閸氬濡甸崟顖ｆ晝闁靛繆鎳ｈ閺屸€崇暆鐎ｎ剛袦闂佽鍠撻崹钘夌暦椤愶箑唯闁挎洍鍋撴繛鍛灴濮婅櫣鎷犻幓鎺濆妷闂佸憡鍨电紞濠傜暦濠婂牊鍋勫瀣濞堥箖姊洪懡銈呮瀾濠㈢懓顑夊銊︾鐎ｎ偄鈧敻鏌ㄥ┑鍡欏嚬缂併劌顭烽弻锝呪攽閸ョ柉鈧寧鎱ㄦ繝鍛仩缂佽鲸甯掕灒婵炶尙绮埛鏍⒒娴ｄ警鐒炬い鎴濇瀹曟繂顫滈埀顒佷繆閻㈢绠涢梺顓ㄩ檮閺咁剙鈹戦鍡欑У闁告鍋愮槐鐐寸節閸屾粍娈惧┑掳鍊愰崑鎾绘婢舵劕绠归柟纰卞幖閺嬫瑩姊洪褍鐏ｉ柍褜鍓濋～澶娒鸿箛娑樺瀭鐟滅増甯掗悡鈥愁熆鐠轰警鐓繛灏栨櫊瀵爼宕奸銈呭辅缂備焦鍞荤换婵嬪蓟閿濆鍋勯柛婵勫劤閻撯偓缂傚倷绀侀ˇ顖滅礊婵犲倻鏆︽繛宸簻閻掑灚銇勯幒宥夋濞存粍绮撻弻鐔煎传閸曨厜銉╂煕韫囨挾鐒搁柡宀€鍠栧畷姗€宕ｆ径濠冪亷闂備礁鎼懟顖滅矓閻戦摪銊︾瑹閳ь剟寮诲☉銏犵閻庨潧鎲￠崳鎵磼閳╁啯宕岄柡灞界Х椤т線鏌涢幘瀵搞€掓俊鍙夊姍楠炴帡骞樼€靛摜肖闂備線娼ц噹闁告侗鍨卞鏍⒑鐠囧弶鍞夋い顐㈩槸鐓ら柍鍝勫暟缁€濠傘€掑锝呬壕闂侀潧妫旂欢姘嚕椤曗偓瀹曠厧鈹戦崱娆戝春濠碉紕鍋戦崐鏍蓟閵婏附娅犲ù鐘差儏濮规煡鏌曡箛瀣偓鏍煕閹烘垯鈧帒顫濋敐鍛婵犵數鍋犳竟鍫濈暦閻㈢绠憸鐗堝笚閸嬪嫰鏌ｉ幘铏崳妞わ富鍣ｅ铏规兜閸涱喖娑ч梻鍌氬鐎氭澘鐣烽幋婢喖鎳￠妶鍥跺晭闂備胶纭堕崜婵嬫晪闂佽楠忕粻鎾诲箖濡も偓椤繈顢橀垾鎰佹闂傚倸娲らˇ鐢稿蓟閵娿儮鏀介柛鈾€鏅滈埢鎾斥攽閳藉棗浜濋柛銊ユ健瀵鏁愭径瀣簻闂佸憡绺块崕鎶芥偪閸曨垱鈷戦悹鍥ｂ偓铏亶濠碉紕鍋樼划娆撴偘椤曗偓楠炴帒螖閳ь剟宕￠幎鑺ョ厽闁哄倹瀵ч幆鍫熺箾閹冲嘲瀚弧鈧紒鐐緲椤﹁京澹曢崸妤佺厱閻庯綆鍋嗗ú瀵糕偓瑙勬礃閸旀瑥鐣风粙璇炬棃宕橀妸褋鍋婇梺璇查缁犲秹宕曢柆宥呯閻庯綆鍠栫憴锕傛倵閿濆骸鏋熼柣鎾存礃缁绘盯骞嬪┑鍡氬煘濡ょ姷鍋為悧婊呮閹烘嚦鏃€鎷呴崨濠呯檨闂備礁鎼惌澶岀礊娓氣偓楠炲啴鍩￠崒娑氱Ф闂佸疇妗ㄧ粈渚€鈥栨径鎰拻濞达絽鎼崝锕傛煕閹惧绠撻悡銈嗙節婵犲倻澧曠紒鐘崇叀閺屾洝绠涢弴鐑嗏偓灞剧箾缁楀搫濮傞柡灞界Х椤т線鏌涢幘瀵告噰闁诡喗鍎抽悾锟犲箯閺冣偓濡啫鐣烽妸鈺婃晣闁搞儯鍎辨慨鍌炴煛鐏炵偓绀冪紒缁樼箞瀹曟帡濡堕崨顓烆棈濠碉紕鍋戦崐褏鈧潧鐭傚畷褰掑醇閺囩偞妲梺缁樺姇閹碱偆绮堥崘鈹夸簻闁哄啫娲ゆ禍褰掓煟閵堝倸浜鹃梻鍌氬€烽懗鍓佸垝椤栫偑鈧啴宕ㄧ€涙ê浜遍梺鍛婂姦閸犳牠宕欓悩缁樼厱闁斥晛鍟ㄦ禒婊堟煛閳ь剚绂掔€ｎ偆鍘介梺褰掑亰閸ㄤ即鎯冮崫鍕电唵鐟滃宕规禒瀣摕婵炴垶鍩冮崑鎾绘晲閸涱収鏆㈢紓浣割樈閸ｏ綁寮诲☉銏犵厴闁诡垎鍌氼棜?
        if (!Boolean.TRUE.equals(config.getVerifyCandidates()) || allCandidates.isEmpty()) {
            markStepSkipped(executionPlan, "VERIFY_TOP_CANDIDATES", "verification skipped because verifyCandidates is disabled or no candidates are available");
            appendSnapshotAndPublish(progressSnapshots, executionPlan, "VERIFY_TOP_CANDIDATES",
                    "verification skipped because verifyCandidates is disabled or no candidates are available", false, null, progressListener, allCandidates, List.of(), null);
        } else if (!resultPageVerificationEnabled) {
            markStepSkipped(executionPlan, "VERIFY_TOP_CANDIDATES",
                    "verification skipped because result page verification is disabled");
            appendSnapshotAndPublish(progressSnapshots, executionPlan, "VERIFY_TOP_CANDIDATES",
                    "verification skipped because result page verification is disabled",
                    false, null, progressListener, allCandidates, List.of(), null);
        } else if (isTimedOut(searchStartedAt, searchTimeoutMillis)) {
            circuitBroken = true;
            degradationReason = "SEARCH_TIMEOUT_BEFORE_VERIFY";
            markStepSkipped(executionPlan, "VERIFY_TOP_CANDIDATES",
                    "verification skipped because search timeout was exhausted before verification");
            appendSnapshotAndPublish(progressSnapshots, executionPlan, "VERIFY_TOP_CANDIDATES",
                    "verification skipped because search timeout was exhausted before verification",
                    true, degradationReason, progressListener, allCandidates, List.of(), null);
        } else {
            markStepRunning(executionPlan, "VERIFY_TOP_CANDIDATES", "verifying top candidates");
            appendSnapshotAndPublish(progressSnapshots, executionPlan, "VERIFY_TOP_CANDIDATES",
                    "verifying top candidates", false, null, progressListener, allCandidates, List.of(), null);
            List<SourceCandidate> verifyCandidates = initialFusionDecision.getVerificationCandidates() == null
                    ? List.of()
                    : initialFusionDecision.getVerificationCandidates();
            CandidateVerificationResult verificationResult = candidateVerifier.verify(
                    config.getCompetitorName(),
                    config.getSourceType(),
                    verifyCandidates
            );
            allCandidates = mergeCandidateUpdates(allCandidates, verificationResult.getUpdatedCandidates());
            appendAttemptedTargets(attemptedTargets, verificationResult.getAttemptedTargets());
            verificationStats.add(verificationResult);
            verifiedCount = verificationResult.getVerifiedTargets().size();
            markStepSuccess(executionPlan, "VERIFY_TOP_CANDIDATES",
                    "verified " + verificationResult.getAttemptedTargets().size() + " candidates, confirmed "
                            + verifiedCount + " targets");
            appendSnapshotAndPublish(progressSnapshots, executionPlan, "VERIFY_TOP_CANDIDATES",
                    "verified " + verificationResult.getAttemptedTargets().size() + " candidates, confirmed "
                            + verifiedCount + " targets",
                    false, null, progressListener, allCandidates, List.of(), null);
        }

        // 闂傚倸鍊搁崐鎼佸磹閻戣姤鍤勯柛顐ｆ礀閸屻劎鎲稿澶嬪仼闁绘垹鐡旈弫鍡涙煕閺囥劌澧柛鎾诡潐缁绘盯骞橀弶鎴犲姲闂佺顑嗛幑鍥蓟閻旂⒈鏁嶆慨妯哄暱椤忣參鏌х紒妯煎ⅹ闂囧鏌ㄥ┑鍡樺櫤閻犳劧绱曠槐鎺撳緞婵犲嫬鐓熷┑顔硷攻濡炶棄螞閸愵喖鐓涘ù锝囧劋琚欓梻鍌欑閹诧繝鈥﹂崼婢盯宕熼姘卞幒?2闂傚倸鍊搁崐鎼佸磹閻戣姤鍊块柨鏃堟暜閸嬫挾绮☉妯诲櫧闁活厽鐟╅弻鐔兼倻濡鏆楅梺宕囨嚀缁夌鐏冮梺鎸庢濡嫰顢氬鍫熺叆婵炴垶锚閳ь剙婀遍幑銏犫攽鐎ｎ偄浠洪梻鍌氱墛娓氭顭囧鑸碘拺闁告稑锕ユ径鍕煕鐎ｎ亜顏柨婵堝仜閻ｆ繈宕熼鈧禒顓炩攽閻樿宸ラ悗姘煎墯閸庮偊姊绘担鍛婂暈濞撴碍顨婂畷浼村冀椤撗勬櫆闂佸綊妫块悞锕傚煕閹寸姷纾藉ù锝堝亗閹次诲宕卞▎灞戒壕閻熸瑥瀚粈鍫ユ煛娴ｅ壊鐓兼鐐插暣閺佹捇鎮╅懠顒夊悈闂備焦瀵у濠氬疾椤愶箑鍌ㄩ梺顒€绉甸埛鎴︽煙缁嬪灝顒㈤柣鎾卞劦閺岀喓鍠婇崡鐐扮凹婵烇絽娲ら敃顏堝箖濠婂牊鍤嶉柕澹啫绗氶梺鑽ゅ枑缁秶鍒掗幘宕囨殾婵犲﹤鍠氬鈺呮煕濡ゅ啫浠滈柣蹇擄躬濮婃椽鎮烽弶搴撴寖缂備緡鍣崹璺虹暦閹存惊鐔兼嚒閵堝浂鍟庨梻浣虹《閸撴繈鏁嬮梺璇查獜缁犳捇骞冨Δ鈧～婵嬵敇閳ユ剚妫熼梻鍌氭搐椤︾敻寮婚妸銉㈡斀闁糕檧鏅滈埢鎾斥攽閳藉棗浜濋柛銊ユ健瀵鏁愭径瀣簻闂佸憡绺块崕鎶芥偪閸曨垱鈷戦悹鍥ｂ偓铏亶濠碉紕鍋樼划娆撴偘椤曗偓楠炴帒螖閳ь剛绮绘繝姘€甸柣銏犳啞濞呮粎绱掓潏顭掕€挎慨濠勭帛缁楃喖鍩€椤掑嫬鐒垫い鎺戝€告禒婊堟煠濞茶鐏￠柡鍛埣椤㈡稑顭ㄩ崨顖ょ床闂佽崵濮村ú锕併亹閸愵喖姹查柨婵嗘礌閸嬫挾鎲撮崟顒傤槬缂傚倸绉撮敃銉︾┍婵犲洤绠瑰ù锝呮憸閸樺憡绻涙潏鍓хК婵℃ぜ鍔庡Σ鎰煥閸曨厾鐦堥梺姹囧灲濞佳冪摥闂備焦瀵уú蹇涘磹濠靛棛鏆︽い鏍仦閸嬫劙鎮归崶顏勭毢闁挎稒鐩娲捶椤撶偘澹曢梺鍝勵儏閵堢顕ｉ崨濠冨劅闁靛濡囬崢鐢电磽閸屾瑩妾烽柛銊ョ秺閹﹢鎮╃憗浣烘嚀椤劑宕熼銏犘戞俊鐐€ら崣鈧繛澶嬫礋楠炴垿宕熼鍌滄嚌濡炪倖鐗楅懝鐐珶鐎ｎ偆绡€闁汇垽娼ф禒褎銇勯幋鐐寸鐎规洘绻傞悾婵嬪礋椤愩倕寮ㄥ┑鐘灱閸╂牠宕濋弴鐘典笉闁煎鍊愰崑鎾舵喆閸曨剛顦ュ┑鐐差檧缁犳挻淇婇悜钘夌厸闁稿本绮岄獮鍫ユ⒒娴ｅ摜绉洪柛瀣躬瀹曟粓鏁冮崒娑樹簵婵犻潧鍊搁幉锟犳偂閺囥垺鐓忓┑鐘茬箳閻ｉ亶鏌涢弬璇测偓婵嬪蓟瀹ュ洦鍠嗛柛鏇ㄥ亞娴煎矂鎮楃憴鍕闁绘牕銈搁崹楣冩晝閸屾氨顓洪梺缁樺姈閸旀牜鎹㈤崼婵愭綎缂備焦蓱婵挳鏌涘☉姗堥練缁绢厸鍋撳┑锛勫亼閸娧呭緤閼测晛鍨濇繛鍡楁禋閸ゆ洟鏌熺紒妯哄潑婵℃彃鐗撻弻鏇＄疀閺囩倫銏ゆ煕鐎ｎ亞效婵﹥妞藉畷顐﹀礋椤撴稒鐎遍梻浣告啞椤牓宕戦幇顓犵彾闁哄洨濮甸崰鍡涙煕閺囥劌骞樻い鏃€娲熷铏瑰寲閺囩偛鈷夐柦鍐憾閹绠涚€ｎ亜顫囬梺鍝勬湰缁嬫捇鍩€椤掑﹦绉甸柛瀣噽娴滄悂骞嶉鐟颁壕闁割煈鍋呯欢鏌ユ倵濮樼厧澧撮柛鈹垮劜瀵板嫭绻濇惔銏犲厞濠碘剝褰冮張顒勬偋濡も偓閺嗏晠姊婚崒姘偓宄懊归崶褏鏆﹂柣銏㈩焾绾惧鏌ｉ幇顔芥毄闁活厽鐟╅悡顐﹀炊閵娧€妲堢紒鐐劤濞硷繝寮婚悢灏佹灁闁割煈鍠楅悘鎾剁磽娴ｅ搫校闁哄被鍔戦垾锕傚锤濡や礁娈濋梻鍌氱墛缁嬫垿锝炲畝鈧槐鎾存媴閹绘帊澹曢梻浣虹《閸撴繈濡甸崒姘ｆ婵炲棙鐟ч惌妤佺箾鏉堝墽绉俊顐㈠瀹曘垽鏁撻悩鏂ユ嫼婵炴潙鍚嬮悷褔鎮炬潏鈺冪＜濠㈣泛锕︾粔娲煙椤曞棛绡€妞ゃ垺娲熼弫鍐焵椤掑嫭鍊峰┑鐘插閸犳劙骞栧ǎ顒€濡奸柛姘秺閺屾盯濡烽鐓庮潽闂佺粯鎸哥换姗€寮诲☉銏犵労闁告劗鍋撻悾鑲╃磽娴ｅ搫鞋閻忓繑鐟уΣ鎰板箳閺傜偓鍕冮梺鑺ッˇ鎶藉春閻愮儤鈷戦悗鍦濞兼劙鏌涢妸銉т虎闁伙綁鏀辩缓浠嬪川婵犲倷绨婚梻浣告啞缁哄潡宕曢幓鎺嗘灁妞ゆ洍鍋撴慨濠勭帛閹峰懘鎸婃径濠冨劒闂備礁鎽滄慨鐢稿礉濞嗘挸绠栭柣鎴ｆ鍞悷婊冾樀瀹曟垿骞橀幇浣瑰兊闂佺粯鎸告鎼佸煕鐎ｎ喗鍊甸悷娆忓缁€鍫ユ煙閾忣偅宕岄柛鈺冨仱楠炲鎮╅顫闂佹寧绻傜花鑲╄姳婵犳碍鐓熸繝闈涙祫閼版寧鎱ㄦ繝鍛仩缂佽鲸甯掕灒闁煎鍊曞鎶芥⒒娴ｅ湱婀介柛鏂跨Ф閹广垽宕煎┑鍫熸闂佺鎻粻鎴犵不婵犳碍鐓涢柛灞久崝婊堟煟鎼粹槅鐓兼慨濠冩そ瀹曠兘顢橀埄鍐锯偓妤呮⒑閹肩偛濡垮褎顨堢划瀣吋婢跺﹦鐣鹃悷婊勭矒閹垽宕卞☉娆忎化闁哄鍋炴刊浠嬵敆閻旈晲绻嗛柤鑹板煐椤忕姷绱掓潏銊ョ闁逞屽墾缂嶅棙绂嶅畡閭﹀晠闁靛鏅滈悡鍐煢濡警妯堟俊顖楀亾婵°倗濮烽崑鐐烘晝閵忋倕绠圭憸鐗堝俯閺佸啴鏌ㄥ┑鍡樺櫣濠㈢懓绉瑰濠氬磼濞嗘埈妲梺纭咁嚋缁绘繈鍨鹃敃鍌氶唶闁靛鍨崇粙蹇旂節閵忥絽鐓愰柛鏃€娲滅划璇差潩閼哥數鍘搁梺鍛婂姂閸斿孩鏅堕弴銏＄厱婵°倕瀚悵顏勄庨崶褝韬い銏＄☉椤繈顢楁担鍥ｆ櫆缁?
        if (shouldSupplement(config, verifiedCount, minVerifiedCount, allCandidates.size(), effectiveTargetCount, resultPageVerificationEnabled)) {
            boolean pendingFieldEvidenceQueries = hasPendingFieldEvidenceQueries(config);
            if (isTimedOut(searchStartedAt, searchTimeoutMillis) && !pendingFieldEvidenceQueries) {
                circuitBroken = true;
                degradationReason = "SEARCH_TIMEOUT_BEFORE_SUPPLEMENT";
                supplementMethod = "TIMEOUT_FALLBACK";
                fallbackDecision = "SKIP_SUPPLEMENT_AND_FALLBACK_PLANNED";
                markStepSkipped(executionPlan, "BROWSER_SUPPLEMENT_SEARCH",
                        "supplement skipped because search timeout was exhausted before supplement");
                appendSnapshotAndPublish(progressSnapshots, executionPlan, "BROWSER_SUPPLEMENT_SEARCH",
                        "supplement skipped because search timeout was exhausted before supplement",
                        true, degradationReason, progressListener, allCandidates, List.of(), null);
            } else {
                String supplementStartMessage = pendingFieldEvidenceQueries
                        ? buildFieldEvidenceSupplementRunningMessage(config)
                        : buildSupplementRunningMessage(config);
                markStepRunning(executionPlan, "BROWSER_SUPPLEMENT_SEARCH", supplementStartMessage);
                appendSnapshotAndPublish(progressSnapshots, executionPlan, "BROWSER_SUPPLEMENT_SEARCH",
                        supplementStartMessage, false, null, progressListener, allCandidates, List.of(), null);
                int supplementTargetPoolSize = resolveSupplementTargetPoolSize(
                        config,
                        resultPageVerificationEnabled,
                        allCandidates.size(),
                        verifiedCount,
                        minVerifiedCount,
                        effectiveTargetCount
                );
                SupplementExecutionOutcome supplementOutcome = executeSupplementByFallbackOrder(
                        config,
                        allCandidates,
                        supplementTargetPoolSize,
                        fieldEvidenceQueryPlan,
                        fieldEvidenceExecutionDeadlineEpochMillis
                );
                List<SourceCandidate> supplementedCandidates = sourceCandidateRanker.rankDeduplicateAndLimit(
                        supplementOutcome.getSupplementedCandidates(),
                        supplementCandidateLimit,
                        maxCandidatesPerDomain
                );
                browserSearchResult = supplementOutcome.getBrowserSearchResult();
                supplementMethod = supplementOutcome.getSupplementMethod();
                providerFallbackUsed = supplementOutcome.isProviderFallbackUsed();
                providerTavilyFastLaneAudit = supplementOutcome.getProviderTavilyFastLaneAudit();
                fallbackDecision = supplementOutcome.getFallbackDecision();
                supplementedCount = supplementedCandidates.size();
                if (!supplementedCandidates.isEmpty()) {
                    allCandidates = sourceCandidateRanker.rankDeduplicateAndLimit(
                            concat(allCandidates, supplementedCandidates),
                            maxCandidatePoolSize,
                            maxCandidatesPerDomain
                    );
                    candidatePoolChangedAfterInitialFusion = true;
                    int needed = Math.max(0, minVerifiedCount - verifiedCount);
                    if (Boolean.TRUE.equals(config.getVerifyCandidates()) && resultPageVerificationEnabled && needed > 0) {
                        if (isTimedOut(searchStartedAt, searchTimeoutMillis)) {
                            circuitBroken = true;
                            degradationReason = "SEARCH_TIMEOUT_AFTER_SUPPLEMENT";
                        } else {
                            CandidateVerificationResult supplementVerification = candidateVerifier.verify(
                                    config.getCompetitorName(),
                                    config.getSourceType(),
                                    supplementedCandidates.stream().limit(needed).toList()
                            );
                            verificationStats.add(supplementVerification);
                            List<SourceCandidate> updatedSupplementCandidates = retainUnverifiedHttpFallbackCandidatesIfNeeded(
                                    config,
                                    supplementOutcome,
                                    supplementVerification
                            );
                            allCandidates = mergeCandidateUpdates(allCandidates, updatedSupplementCandidates);
                            appendAttemptedTargets(attemptedTargets, supplementVerification.getAttemptedTargets());
                            verifiedCount += supplementVerification.getVerifiedTargets().size();
                        }
                    }
                }
                String supplementMessage = supplementedCount == 0
                        ? "supplement produced no new candidates"
                        : "supplement added " + supplementedCount + " candidates; method="
                        + supplementMethod + "; " + browserSearchResult.getSummary();
                if (pendingFieldEvidenceQueries) {
                    supplementMessage += "; " + buildFieldEvidenceSupplementCompletionMessage(
                            fieldEvidenceQueryPlan,
                            providerTavilyFastLaneAudit
                    );
                }
                if (supplementedCount == 0
                        && !"BROWSER_DISABLED_KEEP_PLANNED".equals(fallbackDecision)
                        && !"BROWSER_DISABLED_USE_HTTP_FALLBACK".equals(fallbackDecision)) {
                    fallbackDecision = "NO_NEW_CANDIDATES_KEEP_PLANNED";
                }
                if (circuitBroken && "SEARCH_TIMEOUT_AFTER_SUPPLEMENT".equals(degradationReason)) {
                    supplementMessage += "; timed out before post-supplement verification";
                    fallbackDecision = "SUPPLEMENTED_BUT_SKIP_VERIFY_DUE_TIMEOUT";
                }
                markStepSuccess(executionPlan, "BROWSER_SUPPLEMENT_SEARCH", supplementMessage);
                appendSnapshotAndPublish(progressSnapshots, executionPlan, "BROWSER_SUPPLEMENT_SEARCH",
                        supplementMessage, circuitBroken, degradationReason,
                        progressListener, allCandidates, List.of(), null);
            }
        } else {
            markStepSkipped(executionPlan, "BROWSER_SUPPLEMENT_SEARCH", "supplement skipped because verified candidates already satisfy the target");
            appendSnapshotAndPublish(progressSnapshots, executionPlan, "BROWSER_SUPPLEMENT_SEARCH",
                    "supplement skipped because verified candidates already satisfy the target", false, null, progressListener, allCandidates, List.of(), null);
            fallbackDecision = shouldSkipSupplementForDirectDiscovery(config, verifiedCount, minVerifiedCount)
                    ? "SKIP_SUPPLEMENT_DIRECT_DISCOVERY_ENOUGH"
                    : "SKIP_SUPPLEMENT_ENOUGH_VERIFIED";
        }

        List<SourceCandidate> sitemapCandidates = normalizeCandidates(
                discoverCandidatesFromSitemaps(config, allCandidates),
                "SUPPLEMENTED",
                config
        );
        if (!sitemapCandidates.isEmpty()) {
            allCandidates = sourceCandidateRanker.rankAndDeduplicate(concat(allCandidates, sitemapCandidates));
            candidatePoolChangedAfterInitialFusion = true;
        }

        if (shouldTriggerPublicEvidenceRecovery(config, allCandidates, attemptedTargets)) {
            publicEvidenceRecoveryTriggered = true;
            markStepRunning(executionPlan, "PUBLIC_EVIDENCE_RECOVERY", "running public evidence recovery");
            appendSnapshotAndPublish(progressSnapshots, executionPlan, "PUBLIC_EVIDENCE_RECOVERY",
                    "running public evidence recovery", circuitBroken, degradationReason,
                    progressListener, allCandidates, List.of(), null);

            PublicEvidenceRecoveryService.RecoveryResult recoveryResult = publicEvidenceRecoveryService.recover(
                    PublicEvidenceRecoveryService.RecoveryContext.builder()
                            .competitorName(config.getCompetitorName())
                            .competitorUrls(defaultList(config.getCompetitorUrls()))
                            .sourceType(config.getSourceType())
                            .fieldName(config.getRecoveryFieldName())
                            .evidencePathKey(config.getRecoveryEvidencePathKey())
                            .queryIntents(defaultList(config.getRecoveryQueryIntents()))
                            .seedCandidates(allCandidates)
                            .attemptedTargets(new LinkedHashMap<>(attemptedTargets))
                            .build()
            );
            publicEvidenceAttemptedUrls = recoveryResult.getAttemptedAlternativeUrls() == null
                    ? List.of()
                    : recoveryResult.getAttemptedAlternativeUrls();
            publicEvidenceAttemptedEvidencePaths = recoveryResult.getAttemptedEvidencePaths() == null
                    ? List.of()
                    : recoveryResult.getAttemptedEvidencePaths();
            publicEvidenceRecoveryQueryIntents = recoveryResult.getQueryIntents() == null
                    ? List.of()
                    : recoveryResult.getQueryIntents();

            List<SourceCandidate> recoveryCandidates = normalizeCandidates(
                    removeExistingCandidates(
                            recoveryResult.getCandidates() == null ? List.of() : recoveryResult.getCandidates(),
                            allCandidates
                    ),
                    "SUPPLEMENTED",
                    config
            );
            publicEvidenceRecoveryCandidateCount = recoveryCandidates.size();
            EvidenceRepairPlan recoveryRepairPlan = EvidenceRepairPlan.builder()
                    .state(recoveryCandidates.isEmpty()
                            ? EvidenceRepairState.REPAIR_FAILED
                            : EvidenceRepairState.REPAIR_QUERY_PROPOSED)
                    .reason(recoveryResult.getStatus())
                    .sourceUrl(resolveFirstRecoverySourceUrl(allCandidates))
                    .repairQueries(recoveryCandidates.stream()
                            .map(SourceCandidate::getUrl)
                            .filter(StringUtils::hasText)
                            .toList())
                    .candidateUrls(recoveryCandidates.stream()
                            .map(SourceCandidate::getUrl)
                            .filter(StringUtils::hasText)
                            .toList())
                    .promotedUrls(List.of())
                    .build();
            evidenceRepairPlanProjection = buildRepairAuditProjection(recoveryRepairPlan);
            if (recoveryCandidates.isEmpty()) {
                publicEvidenceRecoveryStatus = "RECOVERY_CANDIDATES_EMPTY";
                markStepSkipped(executionPlan, "PUBLIC_EVIDENCE_RECOVERY", "public evidence recovery produced no candidates");
                appendSnapshotAndPublish(progressSnapshots, executionPlan, "PUBLIC_EVIDENCE_RECOVERY",
                        "public evidence recovery produced no candidates", circuitBroken, degradationReason,
                        progressListener, allCandidates, List.of(), null);
            } else if (Boolean.TRUE.equals(config.getVerifyCandidates()) && resultPageVerificationEnabled) {
                candidatePoolChangedAfterInitialFusion = true;
                CandidateVerificationResult recoveryVerification = candidateVerifier.verify(
                        config.getCompetitorName(),
                        config.getSourceType(),
                        recoveryCandidates
                );
                allCandidates = mergeCandidateUpdates(allCandidates, recoveryVerification.getUpdatedCandidates());
                appendAttemptedTargets(attemptedTargets, recoveryVerification.getAttemptedTargets());
                verificationStats.add(recoveryVerification);
                publicEvidenceRecoveryVerifiedCount = recoveryVerification.getVerifiedTargets() == null
                        ? 0
                        : recoveryVerification.getVerifiedTargets().size();
                verifiedCount += publicEvidenceRecoveryVerifiedCount;
                publicEvidenceRecoveryStatus = publicEvidenceRecoveryVerifiedCount > 0
                        ? "RECOVERED_PUBLIC_PAGE"
                        : "RECOVERY_CANDIDATES_GENERATED";
                evidenceRepairPlanProjection = buildRepairAuditProjection(
                        publicEvidenceRecoveryService.promoteVerifiedUrls(
                                recoveryRepairPlan,
                                recoveryVerification.getVerifiedTargets() == null
                                        ? List.of()
                                        : recoveryVerification.getVerifiedTargets().stream()
                                        .filter(target -> target != null && target.getCandidate() != null)
                                        .map(target -> target.getCandidate().getUrl())
                                        .filter(StringUtils::hasText)
                                        .toList()));
                markStepSuccess(executionPlan, "PUBLIC_EVIDENCE_RECOVERY",
                        publicEvidenceRecoveryVerifiedCount > 0
                                ? "public evidence recovery generated " + recoveryCandidates.size() + " candidates, verified "
                                + publicEvidenceRecoveryVerifiedCount + " targets"
                                : "public evidence recovery generated " + recoveryCandidates.size() + " candidates but none were verified");
                appendSnapshotAndPublish(progressSnapshots, executionPlan, "PUBLIC_EVIDENCE_RECOVERY",
                        publicEvidenceRecoveryVerifiedCount > 0
                                ? "public evidence recovery generated " + recoveryCandidates.size() + " candidates, verified "
                                + publicEvidenceRecoveryVerifiedCount + " targets"
                                : "public evidence recovery generated " + recoveryCandidates.size() + " candidates but none were verified",
                        circuitBroken, degradationReason, progressListener, allCandidates, List.of(), null);
            } else {
                allCandidates = sourceCandidateRanker.rankAndDeduplicate(concat(allCandidates, recoveryCandidates));
                candidatePoolChangedAfterInitialFusion = true;
                publicEvidenceRecoveryStatus = "RECOVERY_CANDIDATES_GENERATED";
                evidenceRepairPlanProjection = buildRepairAuditProjection(recoveryRepairPlan);
                markStepSuccess(executionPlan, "PUBLIC_EVIDENCE_RECOVERY",
                        "public evidence recovery generated " + recoveryCandidates.size() + " candidates and merged them into the pool");
                appendSnapshotAndPublish(progressSnapshots, executionPlan, "PUBLIC_EVIDENCE_RECOVERY",
                        "public evidence recovery generated " + recoveryCandidates.size() + " candidates and merged them into the pool",
                        circuitBroken, degradationReason, progressListener, allCandidates, List.of(), null);
            }
        } else {
            markStepSkipped(executionPlan, "PUBLIC_EVIDENCE_RECOVERY", "public evidence recovery skipped because the candidate pool already satisfies the recovery conditions");
            appendSnapshotAndPublish(progressSnapshots, executionPlan, "PUBLIC_EVIDENCE_RECOVERY",
                    "public evidence recovery skipped because the candidate pool already satisfies the recovery conditions", circuitBroken, degradationReason,
                    progressListener, allCandidates, List.of(), null);
        }

        markStepRunning(executionPlan, "SELECT_TARGETS", "selecting final collection targets");
        appendSnapshotAndPublish(progressSnapshots, executionPlan, "SELECT_TARGETS",
                "selecting final collection targets", circuitBroken, degradationReason,
                progressListener, allCandidates, List.of(), null);

        SearchCandidateFusionDecision finalFusionDecision = initialFusionDecision;
        /*
         * supplement / sitemap / public recovery 闂傚倸鍊搁崐鎼佸磹閹间礁纾瑰瀣椤愪粙鏌ㄩ悢鍝勑㈢紒鎰殕缁绘盯骞嬮悜鍡欏姱濠电偞鍨崹鍦矆鐎ｎ偁浜滈柡鍐ㄦ处閵嗗啯鎱ㄧ憴鍕垫疁婵﹥妞藉畷顐﹀礋椤曞懏钑夐梻浣侯焾鐎涒晠鎮￠垾宕囨殾闁汇垻顭堢粈瀣亜閺嶃劎銆掗柛娆忓暙閳规垿鍩ラ崱妤冧户闁荤姭鍋撻柨鏇楀亾閸楄京鎲搁悧鍫濈瑲妞ゃ儱锕ラ妵鍕箳閹搭厽笑婵犫拃灞芥珝闁硅棄鐖奸幃娆撴倻濡厧寮虫繝鐢靛█濞佳兾涘▎鎾嶅鎮╁顔惧數閻熸粍绮撳畷婊冾潩鐠轰綍?fusion 濠电姷鏁告慨鐑藉极閹间礁纾婚柣鎰惈閸ㄥ倿鏌涢锝嗙闁藉啰鍠栭弻鏇熺箾閻愵剚鐝﹂梺杞扮鐎氫即寮诲☉妯锋婵炲棙鍔楃粙鍥╃磽娴ｅ搫校闁绘顨嗙粚杈ㄧ節閸ヮ灛褔鏌涘☉鍗炲箺婵炲牆澧庣槐鎺楁倷椤掆偓缁€鍐煕鐎ｎ偄濮嶆鐐差槸椤垹鐣濋埀顒勬偪閳ь剙鈹戦悙鏉戠仸闁荤喆鍎茬粋鎺楁倻濡偐鐦堥梺姹囧灲濞佳勭墡闂備浇鍋愰幊鎾存櫠閻ｅ苯鍨濆┑鐘宠壘鍞梺鍐叉惈閸婂宕㈤幘缁樼厸濠㈣泛鑻禒锕€顭块悷鐗堫棤闁圭柉顫夌€佃偐鈧稒顭囬崢閬嶆⒑缂佹ɑ顥堥柡鈧柆宥呯獥婵せ鍋撻柡宀嬬磿娴狅箓宕滆婵洨绱撴担铏瑰笡缂佽鐗嗛悾宄邦潨閳ь剟銆侀弮鍫濆窛妞ゆ挾鍠撹ぐ鎾⒒閸屾艾鈧娆㈠璺虹劦妞ゆ帒鍊告禒婊堟煠濞茶鐏￠柡鍛埣椤㈡瑦鎱ㄩ幇顏嗙泿闂備焦瀵ч弻銊╂倶濠靛棛鏆ら柛鈩冪⊕閻撴瑦銇勯弽銊︾殤濠⒀勬礃閵囧嫰顢樺鍐潎閻庤娲滈崢褔鈥?         * 濠电姷鏁告慨鐑藉极閸涘﹥鍙忛柣鎴濐潟閳ь剙鍊圭粋鎺斺偓锝庝簽閸旓箑顪冮妶鍡楀潑闁稿鎹囬弻娑㈡偄闁垮浠撮梺绯曟杹閸嬫挸顪冮妶鍡楀潑闁稿鎸剧槐鎾愁吋閸滃啳鍚Δ鐘靛仜閸燁偉鐏掗柣鐘叉穿鐏忔瑧绮ｉ悙鐑樼厽閹兼惌鍨崇粔鐢告煕鐎ｎ亜鈧悂锝炲┑瀣櫇闁逞屽墴閸╃偤骞嬮敂钘夆偓鐑芥煕濞嗗浚妯堟俊顐節濮婃椽鎮烽悧鍫熷枑濡炪値鍘奸悧鎾诲春閵忕媭鍚嬪璺猴功娴煎姊洪崫鍕偓鍫曞磿閺屻儻缍栫€广儱顦伴埛鎴︽偡濞嗗繐顏╅柛鏂诲€楅惀顏嗙磼閵忕姴绠洪梺鍝勬噷閸庨潧顫忕紒妯肩懝闁逞屽墮椤洭鎳￠妶鍛瓘婵°倧绲介崯顐ょ不閺嶎厽鐓曢悘鐐村劤閸ゎ剟鏌涢妶鍡樼闁靛洤瀚伴獮鎺楀箣濠垫劒鎮ｇ紓鍌欒兌婵參宕归幎钘夌劦妞ゆ巻鍋撻柛妯荤矒瀹曟垿骞橀弬銉︽杸闂佺粯蓱瑜板啴寮抽悢铏圭＜濠㈣泛鑻崢瀛樻叏婵犲嫮甯涢柟宄版嚇瀹曘劍绻濋崒娑欑暭闂備焦鐪归崺鍕垂闁秲鈧啴宕奸妷銉︾€銈嗘⒒閺咁偆寮ч埀顒勬⒑閸涘﹥澶勯柛妯煎帶閻ｇ兘濡烽埡鍌楁嫼闂佸憡绻傜€氬嘲危瑜版帗鐓曢柕濞у啯鐏堥悗娈垮枛閹诧繝骞嗛弮鍫澪╅柨鏃€鍎崇敮妤呮⒒娴ｈ棄袚闁挎碍銇勯敂鍨祮鐎规洘濞婇幃鎯х暆閳ь剛澹曟總鍛婄厓鐟滄粓宕滃顒夊殫闁告洦鍨扮粻娑欍亜閹捐泛孝妤犵偞鍔欏缁樻媴缁嬫妫岄梺绋款儏閹虫﹢骞婂┑瀣€锋い鎺戝亞濞叉悂姊洪崨濠冨瘷闁告侗鍨界槐鎶芥⒒娴ｄ警鐒鹃柡鍫墴閹柉顦归挊婵嬫煥閺囩偛鈧綊鎮￠悢闀愮箚闁靛牆瀚崝宥団偓瑙勬礀閻倿寮诲☉銏犵厴闁诡垎鍌氼棜婵犵绱曢崑鎴﹀磹閺嵮屽晠濠电姵鑹剧壕濠氭煙閻愵剛鏆樺ù婊勭矒閺屻劑寮崶顭戞濡炪們鍎遍澶愬蓟閵堝悿娲敂閸愨晛鏋ゆ俊鐐€戦崹娲偡閳哄懎绠板┑鐘插暙缁剁偞淇婇婊冨妺妞ゆ梹鎸搁埞鎴︽偐閹颁礁鏅遍梺鍝ュУ椤ㄥ﹪鍨鹃敃鍌氶唶闁绘梻绻濈划鎾绘⒑瑜版帗锛熺紒鈧笟鈧浼村Ψ閿斿墽顔曢梺鐟邦嚟閸嬬偤鎯冮幋鐘垫／闁硅鍔栭ˉ澶愭煏閸℃ê绗掓い顐ｇ箞椤㈡鎷呯憴鍕偓閿嬩繆閵堝洤啸闁稿鍋ら妴鍐╃節閸屻倖缍庡┑鐐叉▕娴滃爼寮繝鍥ㄧ厱婵犻潧妫楅鈺傘亜椤愩垺鍠樻慨濠呮閹风娀鍨鹃搹顐や簽缂傚倷绶￠崰妤呮偡閿旂晫鈹嶅┑鐘叉搐缁犵懓霉閿濆牆鈧粙鍩￠崨顔尖偓鐢告煥濠靛棛鍑归柟鑼亾娣囧﹪宕ｆ径瀣偓鎰版煛鐏炵晫效闁诡喚鍏樺鍫曞箰鎼淬埄鍟嬪┑掳鍊楁慨鐑藉磻濞戞◤娲敇椤兘鍋撴担鑲濇棃宕ㄩ鐘插Е婵＄偑鍊栫敮鎺撶箾閸岀偞鍊婚柦妯侯槸缁愭盯鏌ｆ惔銏⑩姇妞ゎ厼娲畷?effectiveTargetCount闂?         * 闂傚倸鍊搁崐宄懊归崶顒夋晪鐟滃繘鎳為柆宥嗗殐闁宠桨鑳剁粵蹇曠磽閸屾瑧鍔嶆い顓炴喘閹敻宕奸弴鐔哄幈濡炪倖鍔楁慨鎾礉濮樿埖鐓?search-first 闂傚倸鍊搁崐鎼佸磹妞嬪海鐭嗗〒姘ｅ亾妤犵偞鐗犻、鏇㈠Χ閸屾矮澹曞┑顔筋焽閸樠勬櫠娴煎瓨鐓冪憸婊堝礈濮樿京鐭欓柛顐犲劚閻ょ偓绻涢幋鐏活亞绮婇锔解拻濞达絿鍎ら崵鈧梺瀹︽澘濡块柟骞垮灩椤撳吋寰勬繝鍕劸闂備礁鎲＄粙鎴︽偤閵娾晜鍋傞柣妯兼暩濡垶鏌℃径濠勪虎闁煎摜鎳撻…鑳槺闁告濞婂濠氭晲婢跺á銊╂煏婢舵稓鐣辨繛鍫燁殜濮婅櫣鎷犻垾铏仌濠电偛顦伴惄顖炲春閵夛箑绶為柟閭﹀墮閸炪劑鎮峰鍐ｉ柟渚垮姂婵″爼宕堕埡鍐跨闯濠电偠鎻紞鈧繛鍜冪悼閺侇喖鈽夐姀锛勫弳濠殿喗锕╅崢瑙勭濠婂牊鐓涢柛銉︽构缁ㄧ晫绱掓径妯烘灓闁逞屽墲椤煤閿曞倸纾诲┑鐘插€婚弳锕傛煟閺冨倸甯堕幆鐔兼⒑闂堟侗妲堕柛濠冩礋钘熸慨姗嗗幘缁♀偓闂佹眹鍨藉褎绂掑鍕箚妞ゆ劧绲块幊鍥殽閻愭潙濮嶆鐐达耿椤㈡瑩鎳栭埡瀣耿闂傚倷绀佸﹢閬嶅磻閹炬剚鐒芥繛鍡樺灦椤愪粙鏌ｉ幇顒夊殶缂佺娀绠栭弻鐔衡偓鐢殿焾娴狅箓鏌ｉ妸锕€鐏﹂柕鍥у閺佸倻鎷犻崣澶屽綆婵犳鍠栭敃銉ヮ渻閽樺鏆﹂柕濠忓缁♀偓闁瑰吋鐣崹濠氬汲椤撶姷纾介柛灞剧懆閸忓瞼绱掗鍛仯闁瑰箍鍨藉畷鐔碱敍濮橀硸妲撮梻浣告贡閸庛倝宕归悢鑲猴綁宕奸妷锔惧帾闂婎偄娲﹀ú鏍ф毄闂備胶绮幐濠氬垂閻㈢硶鈧棃宕橀鍢壯囨煕濞戝崬鐏ｆい鏂挎喘濮婃椽宕妷銉︾€炬繝銏㈡嚀濡盯宕氶幒鏃傜＜婵☆垳鈷堝ù鍕煟鎼搭垳绉甸柛瀣瀹?direct seed 闂傚倸鍊搁崐鎼佸磹瀹勬噴褰掑炊瑜滃ù鏍煏婵炲灝鍔存繛鎾愁煼閺岀喖鎮滃鍡樼暥缂佺虎鍘搁崑鎾绘⒒娴ｇ瓔娼愰柛搴ｅ帶铻為柛鏇ㄥ灡閳锋帗銇勯弽顐沪闁绘搫缍侀悡顐﹀炊閵婏箑闉嶉柣鐘冲姀閸撴繃绌辨繝鍥舵晝妞ゆ劑鍨绘禒顖炴⒑閻熸澘绾ч柣鈩冩礈缁鈽夐姀鐘殿啋濡炪倖鐗楃粙鎾寸閺嶎厽鈷掑ù锝囧劋閸も偓濡炪倖娲﹂崢浠嬪箞閵婏富妲归幖绮规濡粍绻濋棃娑樷偓鎼佸箟閿熺姵鍋傛繛鍡樻尰閻撴瑦銇勯弽褎顥滈柤娲诲灠閳绘捇鎳滈悽鐢殿啎闁诲孩绋掗…鍥儗閵堝鐓曢柕濞垮劤閸╋絿鈧?         */
        if (candidatePoolChangedAfterInitialFusion) {
            finalFusionDecision = searchCandidateFusionPlanner.plan(
                    config,
                    allCandidates,
                    targetCount,
                    maxCandidatesPerDomain
            );
            if (finalFusionDecision.getRankedCandidates() != null && !finalFusionDecision.getRankedCandidates().isEmpty()) {
                allCandidates = finalFusionDecision.getRankedCandidates();
            }
        }
        effectiveTargetCount = finalFusionDecision.getEffectiveTargetCount();

        SearchSelectionDecision selectionDecision = collectionTargetSelector.selectTargets(
                allCandidates,
                attemptedTargets,
                effectiveTargetCount
        );
        List<SearchCollectionTarget> selectedTargets = selectionDecision.getSelectedTargets() == null
                ? List.of()
                : selectionDecision.getSelectedTargets();
        allCandidates = selectionDecision.getUpdatedCandidates() == null
                ? allCandidates
                : selectionDecision.getUpdatedCandidates();
        List<SearchCollectionTarget> attemptedTargetList = new ArrayList<>(attemptedTargets.values());
        List<SourceCandidate> discardedCandidates = selectionDecision.getDiscardedCandidates() == null
                ? new ArrayList<>()
                : new ArrayList<>(selectionDecision.getDiscardedCandidates());
        if (!explicitUrlRejectedCandidates.isEmpty()) {
            discardedCandidates.addAll(0, explicitUrlRejectedCandidates);
        }
        markStepSuccess(executionPlan, "SELECT_TARGETS",
                "selected " + selectedTargets.size() + " final targets");
        appendSnapshotAndPublish(progressSnapshots, executionPlan, "SELECT_TARGETS",
                "selected " + selectedTargets.size() + " final targets", circuitBroken, degradationReason,
                progressListener, allCandidates, selectedTargets, null);

        TavilyFastLaneAudit tavilyFastLaneAudit = buildTavilyFastLaneAudit(
                allCandidates,
                selectedTargets,
                providerFallbackUsed,
                providerTavilyFastLaneAudit
        );
        FieldEvidenceExecutionStats fieldEvidenceExecutionStats = resolveFieldEvidenceExecutionStats(
                fieldEvidenceQueryPlan,
                providerTavilyFastLaneAudit
        );

        SearchExecutionTrace executionTrace = SearchExecutionTrace.builder()
                .traceVersion("v1")
                .searchMode(config.getSearchMode())
                .searchQueries(executionPlan.getSearchQueries() == null ? List.of() : executionPlan.getSearchQueries())
                .fallbackOrder(executionPlan.getFallbackOrder() == null ? List.of() : executionPlan.getFallbackOrder())
                .plannedCandidateCount(config.getSourceCandidates() == null ? 0 : config.getSourceCandidates().size())
                .requestedTargetCount(targetCount)
                .effectiveTargetCount(effectiveTargetCount)
                .searchFirstMinimumTargetCount(searchPolicyResolver.resolveSearchFirstMinimumTargetCount(config))
                .targetCountReason(searchPolicyResolver.resolveTargetCountReason(config, targetCount, effectiveTargetCount))
                .baseTargetCount(targetCount)
                .preSupplementEffectiveSearchFirstTargetCount(initialFusionDecision.getEffectiveTargetCount())
                .preSupplementFusionRankedCandidateCount(initialFusionDecision.getRankedCandidates() == null ? 0 : initialFusionDecision.getRankedCandidates().size())
                .preSupplementFusionPreselectedCandidateCount(initialFusionDecision.getPreselectedCandidates() == null ? 0 : initialFusionDecision.getPreselectedCandidates().size())
                .preSupplementFusionFastLaneCandidateCount(initialFusionDecision.getFastLaneCandidateCount())
                .preSupplementFusionVerificationCandidateCount(initialFusionDecision.getVerificationCandidateCount())
                .preSupplementFusionThirdPartyCandidateCount(initialFusionDecision.getThirdPartyCandidateCount())
                .effectiveSearchFirstTargetCount(effectiveTargetCount)
                .fusionRankedCandidateCount(finalFusionDecision.getRankedCandidates() == null ? 0 : finalFusionDecision.getRankedCandidates().size())
                .fusionPreselectedCandidateCount(finalFusionDecision.getPreselectedCandidates() == null ? 0 : finalFusionDecision.getPreselectedCandidates().size())
                .fusionFastLaneCandidateCount(finalFusionDecision.getFastLaneCandidateCount())
                .fusionVerificationCandidateCount(finalFusionDecision.getVerificationCandidateCount())
                .fusionThirdPartyCandidateCount(finalFusionDecision.getThirdPartyCandidateCount())
                .directSeedCandidateCount(finalFusionDecision.getDirectSeedCandidateCount())
                .attemptedCandidateCount(attemptedTargetList.size())
                .discardedCandidateCount(discardedCandidates.size())
                .verifiedCandidateCount(verifiedCount)
                .supplementedCandidateCount(supplementedCount)
                .candidateVerificationElapsedMillis(verificationStats.getElapsedMillis())
                .candidateVerificationConcurrency(verificationStats.getMaxConcurrency())
                .candidateVerificationInputCount(verificationStats.getInputCount())
                .candidateVerificationUniqueCount(verificationStats.getUniqueCount())
                .candidateVerificationReusedPageCount(verificationStats.getReusedCollectedPageCount())
                .candidateVerificationDirectAttemptCount(verificationStats.getDirectAttemptCount())
                .candidateVerificationDirectUsableCount(verificationStats.getDirectUsableCount())
                .candidateVerificationDirectShortcutCount(verificationStats.getDirectShortcutCount())
                .supplementMethod(supplementMethod)
                .browserSearchEngine(browserSearchResult.getSearchEngine())
                .browserTraceId(browserSearchResult.getBrowserTraceId())
                .browserExecutedQueries(browserSearchResult.getExecutedQueries() == null ? List.of() : browserSearchResult.getExecutedQueries())
                .browserSearchSummary(browserSearchResult.getSummary())
                .browserFailureKind(browserSearchResult.getFailureKind())
                .browserRestartScope(browserSearchResult.getRestartScope())
                .browserFallbackAction(browserSearchResult.getFallbackAction())
                .browserMatchedSignals(browserSearchResult.getMatchedSignals() == null ? List.of() : browserSearchResult.getMatchedSignals())
                .providerFallbackUsed(providerFallbackUsed)
                .selectedCandidateCount(selectedTargets.size())
                .searchTimeoutMillis(searchTimeoutMillis)
                .searchElapsedMillis(System.currentTimeMillis() - searchStartedAt)
                .circuitBroken(circuitBroken)
                .degraded(circuitBroken)
                .degradationReason(degradationReason)
                .browserBlockedReason(browserSearchResult.getBlockedReason())
                .browserBlockedCount(browserSearchResult.getBlockedCount())
                .fallbackDecision(fallbackDecision)
                .recoveryCheckpoint(resolveRecoveryCheckpoint(executionPlan))
                .recoveryAdvice(buildRecoveryAdvice(circuitBroken, degradationReason, browserSearchResult, selectedTargets, config))
                .publicEvidenceRecoveryTriggered(publicEvidenceRecoveryTriggered)
                .publicEvidenceAttemptedUrls(publicEvidenceAttemptedUrls)
                .publicEvidenceAttemptedEvidencePaths(publicEvidenceAttemptedEvidencePaths)
                .publicEvidenceRecoveryFieldName(config.getRecoveryFieldName())
                .publicEvidenceRecoveryEvidencePathKey(config.getRecoveryEvidencePathKey())
                .publicEvidenceRecoveryQueryIntents(publicEvidenceRecoveryQueryIntents)
                .publicEvidenceRecoveryCandidateCount(publicEvidenceRecoveryCandidateCount)
                .publicEvidenceRecoveryVerifiedCount(publicEvidenceRecoveryVerifiedCount)
                .publicEvidenceRecoveryStatus(publicEvidenceRecoveryStatus)
                .fieldEvidenceQueryCount(fieldEvidenceQueryPlan.getPlanned().size())
                .fieldEvidenceQueryPlannedCount(fieldEvidenceExecutionStats.getPlannedCount())
                .fieldEvidenceQueryExecutedCount(fieldEvidenceExecutionStats.getExecutedCount())
                .fieldEvidenceQuerySkippedCount(fieldEvidenceExecutionStats.getSkippedCount())
                .fieldEvidenceQuerySkipReasons(fieldEvidenceExecutionStats.getSkipReasons())
                .fieldEvidenceFields(resolveDistinctFieldEvidenceFields(fieldEvidenceQueryPlan.getPlanned()))
                .fieldEvidencePaths(resolveDistinctFieldEvidencePaths(fieldEvidenceQueryPlan.getPlanned()))
                .evidenceRepairPlan(evidenceRepairPlanProjection)
                .tavilyFastLaneAudit(tavilyFastLaneAudit)
                .resumedFromCheckpoint(resumedFromCheckpoint)
                .checkpointSource(checkpointSource)
                .runtimePolicy(resolveRuntimePolicy(config))
                .selectedUrls(selectionDecision.getSourceUrls() == null ? List.of() : selectionDecision.getSourceUrls())
                .generatedAt(LocalDateTime.now())
                .build();
        SearchAuditSummary auditSummary = buildSearchAuditSummary(
                allCandidates,
                attemptedTargetList,
                selectedTargets,
                discardedCandidates,
                executionTrace,
                tavilyFastLaneAudit
        );
        publishProgress(progressListener, executionPlan, progressSnapshots, allCandidates, selectedTargets, executionTrace);
        List<SearchReplayTimelineItem> replayTimeline = buildReplayTimeline(
                progressSnapshots,
                allCandidates,
                attemptedTargetList,
                selectedTargets,
                discardedCandidates,
                executionTrace.getSelectedUrls());

        SearchProgressSnapshot latestProgress = progressSnapshots.isEmpty()
                ? buildProgressSnapshot(executionPlan, "LOAD_CANDIDATES", "search execution not started", false, null)
                : progressSnapshots.get(progressSnapshots.size() - 1);
        String reasoningSummary = "plannedCandidates=" + executionTrace.getPlannedCandidateCount()
                + ", verifiedTargets=" + verifiedCount
                + ", supplementedCandidates=" + supplementedCount
                + ", selectedTargets=" + selectedTargets.size()
                + ", supplementMethod=" + supplementMethod;
        if (circuitBroken && StringUtils.hasText(degradationReason)) {
            reasoningSummary += "; degradationReason=" + degradationReason;
        }
        if (StringUtils.hasText(executionTrace.getBrowserBlockedReason())) {
            reasoningSummary += "; browserBlockedReason=" + executionTrace.getBrowserBlockedReason();
        }

        return SearchExecutionResult.builder()
                .executionPlan(executionPlan)
                .progressSnapshot(latestProgress)
                .progressSnapshots(progressSnapshots)
                .sourceCandidates(allCandidates)
                .attemptedTargets(attemptedTargetList)
                .selectedTargets(selectedTargets)
                .discardedCandidates(discardedCandidates)
                .replayTimeline(replayTimeline)
                .reasoningSummary(reasoningSummary)
                .executionTrace(executionTrace)
                .auditSnapshot(SearchAuditSnapshot.builder()
                        .summary(auditSummary)
                        .executionTrace(executionTrace)
                        .executionPlan(executionPlan)
                        .latestProgress(latestProgress)
                        .progressHistory(progressSnapshots)
                        .tavilyFastLaneAudit(tavilyFastLaneAudit)
                        .evidenceRepairPlan(evidenceRepairPlanProjection)
                        .replayTimeline(replayTimeline)
                        .sourceCandidates(allCandidates)
                        .attemptedTargets(attemptedTargetList)
                        .selectedTargets(selectedTargets)
                        .discardedCandidates(discardedCandidates)
                        .sourceUrls(executionTrace.getSelectedUrls())
                        .build())
                .build();
    }

    private List<SourceCandidate> resolveCandidatesFromCheckpoint(SearchAuditSnapshot checkpoint) {
        if (checkpoint == null || checkpoint.getSourceCandidates() == null || checkpoint.getSourceCandidates().isEmpty()) {
            return List.of();
        }
        return checkpoint.getSourceCandidates();
    }

    private Map<String, SearchCollectionTarget> resolveAttemptedTargetsFromCheckpoint(SearchAuditSnapshot checkpoint) {
        Map<String, SearchCollectionTarget> attemptedTargets = new LinkedHashMap<>();
        if (checkpoint == null) {
            return attemptedTargets;
        }
        if (checkpoint.getAttemptedTargets() != null && !checkpoint.getAttemptedTargets().isEmpty()) {
            appendAttemptedTargets(attemptedTargets, checkpoint.getAttemptedTargets());
            return attemptedTargets;
        }
        appendAttemptedTargets(attemptedTargets, checkpoint.getSelectedTargets());
        return attemptedTargets;
    }

    private SourceCandidate buildExplicitConfiguredCandidate(CollectorNodeConfig config, String url) {
        return SourceCandidate.builder()
                .url(url)
                .title(config.getCompetitorName() + " - " + safeSourceType(config.getSourceType()) + " entry")
                .sourceType(safeSourceType(config.getSourceType()))
                .discoveryMethod("DIRECT_LOCATOR")
                .providerKey("planned")
                .reason(StringUtils.hasText(config.getDiscoveryNotes())
                        ? config.getDiscoveryNotes()
                        : "闂傚倸鍊搁崐鎼佸磹閻戣姤鍤勯柛顐ｆ礀閸屻劎鎲搁弮鍫㈠祦闁哄稁鍙庨弫鍐煥閺囨浜剧紒鎯у⒔閹虫捇鍩為幋锔藉亹閻庡湱濮撮ˉ婵堢磼閻愵剙鍔ゆい顓犲厴瀵濡搁妷銏℃杸闂佺硶妾ч弲婊勬櫏闂傚倷鑳剁划顖炲箰閹绢喖纾婚柟鍓х帛閻撶喖骞栨潏鍓х？闁伙綆鍙冮弻娑欐償閳ュ疇鍩為柣鎾卞€濋弻鏇熺箾閻愵剚鐝旈梺鍛婂灩婵炩偓闁哄本绋戦悾婵嬪焵椤掑嫬纾婚柣鎰劋閸嬪倿鏌ｉ弬鍨倯闁绘挸鍟村鍫曟倷閺夋埈鈧粓鏌涜箛鎾剁劯闁哄瞼鍠栭幃鍓т沪鐟欙絾鐎伴梻浣筋嚃閸犳洜鍒掑▎鎾扁偓浣糕槈濡攱顫嶅┑鐐叉缁绘劙寮ㄩ鐔剁箚闁靛牆娲ゅ暩闂佺顑囬崑銈夌嵁閹达箑鐐婃い鎺戝€哥粊锕傛⒑閹肩偛鍔撮柛鎿勭畵瀵偊宕掗悙瀵稿幈闂佹枼鏅涢崯顖炴儍閹寸姷纾奸柣妯虹－閸欌偓濠殿喖锕ら…宄扮暦閹烘垟鏋庨柟瀛樼箓椤鏌ｉ悢鍝ョ煁婵犮垺锕㈠畷顖烆敃閵堝懎鐤惧┑锛勫亼閸婃牠骞愭ィ鍐ㄧ；闁圭増婢橀崙?URL")
                .domain(extractDomain(url))
                .sourceUrls(List.of(url))
                .relevanceScore(0.82)
                .freshnessScore(0.55)
                .qualityScore(0.80)
                .selectionStage("PLANNED")
                .selectionReason("generated directly from explicit competitorUrls")
                .build();
    }

    private InitialCandidateResolution resolveInitialCandidatesWithDiagnostics(CollectorNodeConfig config) {
        if (config.getSourceCandidates() != null && !config.getSourceCandidates().isEmpty()) {
            return mergeConfiguredCandidatesWithExplicitUrlsWithDiagnostics(
                    config,
                    config.getSourceCandidates().stream()
                            .filter(Objects::nonNull)
                            .toList()
            );
        }
        List<SourceCandidate> directCandidates = directDiscoveryPlanner.buildInitialCandidates(
                config.getCompetitorName(),
                safeSourceType(config.getSourceType()),
                defaultList(config.getCompetitorUrls())
        ).stream()
                .filter(candidate -> candidate != null && safeSourceType(config.getSourceType()).equals(candidate.getSourceType()))
                .toList();
        return mergeConfiguredCandidatesWithExplicitUrlsWithDiagnostics(config, directCandidates);
    }

    private InitialCandidateResolution mergeConfiguredCandidatesWithExplicitUrlsWithDiagnostics(CollectorNodeConfig config,
                                                                                                List<SourceCandidate> baseCandidates) {
        List<SourceCandidate> mergedCandidates = new ArrayList<>(baseCandidates == null
                ? List.of()
                : baseCandidates.stream()
                .filter(Objects::nonNull)
                .toList());
        List<SourceCandidate> rejectedCandidates = new ArrayList<>();
        Set<String> seenCanonicalUrls = new LinkedHashSet<>();
        for (SourceCandidate candidate : mergedCandidates) {
            String canonicalUrl = canonicalUrlResolver.canonicalize(candidate.getUrl());
            if (StringUtils.hasText(canonicalUrl)) {
                seenCanonicalUrls.add(canonicalUrl);
            }
        }
        for (String url : defaultList(config.getCompetitorUrls())) {
            if (!StringUtils.hasText(url)) {
                continue;
            }
            String canonicalUrl = canonicalUrlResolver.canonicalize(url);
            /*
             * 闂傚倸鍊搁崐鎼佸磹妞嬪海鐭嗗〒姘ｅ亾妤犵偞鐗犻、鏇㈠Χ閸屾矮澹曞┑顔筋焽閸樠冾嚕椤旈敮鍋撶憴鍕婵炲弶鐗犻幃楣冩倻閽樺鍊炲銈庡幗閸ㄥ墎绱?competitorUrls 濠电姷鏁告慨鐑藉极閹间礁纾婚柣鎰惈閸ㄥ倿鏌涢锝嗙缂佺姳鍗抽弻娑樷攽閸曨偄濮㈤梺娲诲幗閹瑰洭寮婚敐澶婄闁挎繂妫Λ鍕磽娴ｆ彃浜鹃梺绋挎湰缁嬫帡宕ｈ箛鏂剧箚闁绘劙顤傞崵娆徝瑰鍫㈢暫闁诡喗顨堥幉鎾礋椤掑偆妲伴梻浣瑰绾板秹濡甸崟顖氱闁告劕寮堕崐顖炴倵閸偅绶查悗姘煎櫍閸┾偓妞ゆ帒锕︾粔鐢告煕閻樿櫕宕岀€规洘鍨佃灃闁逞屽墴閸╃偤骞嬮敂缁樻櫓闂佽姤锚椤﹂亶鎮烽懠顒傜＝濞达絽澹婇崕鎰版偨椤栨粌浠滈柨鏇樺灪閹峰懐鍖栭弴鐔告澑闂備胶绮崝鏍ь焽濞嗘挻鍊块柟顖ｇ亹閻熼偊鐓ラ柛鏇ㄥ幘閻撳顪冮妶鍐ㄧ仾鐎光偓閹间礁鏋侀柟閭﹀幖缁剁偛鈹戦悩鎻掝伌婵¤弓鍗冲缁樻媴閻熼偊鍤嬪┑顔硷工椤兘鐛繝鍥х閻庨潧澹婂ú?             * 闂傚倸鍊搁崐鎼佸磹妞嬪海鐭嗗〒姘ｅ亾妤犵偛顦甸弫鎾绘偐椤旂懓浜鹃柛鎰靛枛瀹告繈鏌℃径瀣仴闁稿绉瑰娲传閸曨厸鏋嗛梺绋款儏濡繈骞?canonicalize 濠电姷鏁告慨鐑藉极閸涘﹥鍙忓ù鍏兼綑閸ㄥ倹绻涘顔荤盎缁炬儳娼￠弻銈吤圭€ｎ偅鐝旈梺鎼炲妽缁诲牓寮诲☉銏犵婵°倐鍋撻悗姘煎墴瀵悂宕掗悙绮规嫼闂佸湱顭堢€涒晝绮堥埀顒勬⒑缁嬪尅宸ラ柟鑺ョ矌閸掓帗绻濆顓熸珳婵犮垼娉涢鍥储閹间焦鍊甸悷娆忓閹嫰鏌涢悩宕囧⒈闁逞屽墯閸戝綊宕滃▎鎾崇厺闁规崘顕у敮闂佹寧鏌ㄦ晶浠嬵敊閺囥垺鈷戦柣鐔煎亰閸ょ喖鏌涚€ｎ剙浠辨鐐叉瀹曠喖顢涘☉姘箞婵犵數鍋涘Λ妤冩崲閹版澘姹查柨婵嗘礌閸嬫挾鎲撮崟顒傤槰婵犵數鍋涢敃銈夋偩閻戣棄惟闁挎柨澧介惁鍫ユ⒑缁嬫寧婀扮紒顔奸叄閹?canonical闂傚倸鍊搁崐鎼佸磹閻戣姤鍊块柨鏃堟暜閸嬫挾绮☉妯诲櫧闁活厽鐟╅弻鐔衡偓鐢殿焾娴犙囨⒒閸曨偄顏柡宀嬬節瀹曟﹢濡搁妷銏犱壕闁告稑锕ラ崣蹇斻亜閹烘垵顏柣鎾存礋閺岀喖寮堕崹顔藉€庣紓浣靛妼閵堟悂寮诲☉銏″亜闂佸灝顑嗛崕鎾绘⒒閸パ屾█闁哄被鍔岄埞鎴﹀幢濡鍋愰梻浣虹帛鐢紕绮婚弽顓炶摕闁跨喓濮村婵囥亜閺冨洦顥夐柍褜鍓氶崝妤呭焵?discarded audit闂?             * 闂傚倸鍊搁崐椋庣矆娓氣偓楠炴牠顢曚綅閸ヮ剦鏁冮柨鏇楀亾闁汇倗鍋撶换婵囩節閸屾粌顤€闂佺顑戠换婵嬪蓟閵娾晛鍗抽柣鎰ゴ閸嬫捁銇愰幒鎴狅紱闁诲函缍嗛崰妤呭煕閹寸偑浜滈柟鍝勬娴滃墽绱撴担鍓叉Ч闁瑰憡濞婇獮鍐ㄢ枎閹邦喚鐦堥梺鎼炲劘閸斿酣宕㈡禒瀣拺鐟滅増甯掓禍浼存煕閹惧娲存鐐搭殜閹晝绱掑Ο鐓庡箰濠电姰鍨煎▔娑㈡晝閿斿墽鐭撴繛宸簼閻撴稓鈧厜鍋撻悗锝庡墰閻﹀牓鎮楃憴鍕闁挎洏鍨介妴浣割潨閳ь剟骞冨▎鎾搭棃婵炴垶顭囩槐鏉库攽閻樺灚鏆╁┑顕呭弮楠炲繘宕橀鐓庣獩濡炪倖鎸荤粙鎺楁倵娴煎瓨鈷掑ù锝勮閺€鐗堢箾閸涱喗绀嬮柕鍡楀暞缁绘繈宕掑鍕啎闂備浇顫夋竟鍡樻櫠濡ゅ懎纾婚柛鏇ㄥ墰缁♀偓婵犵數濮撮崐鎼侇敂閳哄倻绠鹃柟纰卞幖濞呭秹鏌″畝瀣？濞寸媴绠撻幊鐐哄Ψ閿旇棄顏扮紓鍌氬€烽懗鍓佸垝椤栫偞鍎庢い鏍仜閽冪喖鏌曟繛鐐珦闁轰礁绉甸幈銊ヮ潨閸℃ぞ绨诲銈冨劜绾板秶鎹㈠┑瀣仺闂傚牊鍒€閵忋倖鐓ラ柡鍥埀顒佺箞閻涱喗绻濋崨顖滄澑濠电偞鍨惰摫閺夊牆鐗撳Λ鍛搭敃閵忊€愁槱缂備礁顑嗙敮锟犲箖閳ユ枼鏋庨柟鎯ь嚟閸樼數绱撻崒娆撴闁搞劌缍婂鎼佹偄閸忚偐鍘告繛杈剧导缁瑩宕崫鍕勫酣宕惰闊剛鈧娲╃徊鎯ь嚗閸曨剛绡€闁告洦浜炵粈澶愭⒑鐠囨彃顒㈡い鏃€鐗犲畷鏉款潩椤撶喐鐝峰┑掳鍊曢幊搴ｇ不濮樿埖鐓涢柛鎰╁妿婢ф洟骞嗛悢鍏尖拺闂傚牊渚楀Σ鍫曟煕鎼粹槅鍤熺紒顔硷躬瀵爼骞嬮弮鈧弬鈧?URL闂傚倸鍊搁崐鎼佸磹閻戣姤鍊块柨鏃堟暜閸嬫挾绮☉妯诲櫧闁活厽鐟╅弻鐔衡偓鐢殿焾娴犙囨⒒閸曨偄顏柡宀嬬節瀹曟﹢濡搁妷銏犱壕闁革富鍘搁崑鎾愁潩椤掑鍚嬮梺鍝勭焿缂嶄礁顕ｉ鍕閹兼番鍨归弸鎴炰繆閻愵亜鈧劙寮插┑瀣婵せ鍋撶€殿喛顕ч濂稿醇椤愶綆鈧洭姊绘担鍛婂暈闁规瓕顕ч～婵嬪Ω瑜嶉崹婵囩箾閸℃ê濮夌紒鈾€鍋撻梻浣规偠閸庢粓宕惰閺嗩亪姊婚崒娆掑厡缂侇噮鍨伴～蹇旂節濮橆剛锛熼梺闈涚墕椤︻垶鎮￠弴銏＄厸闁搞儯鍎遍悘顏堟煃闁垮鐏撮柡灞剧☉閳规垿宕卞Δ濠佺磻濠电姭鎷冮崟顒傤槰闂侀潧娲ょ€氫即寮崘顔肩劦妞ゆ帒鍊搁ˉ姘亜閹惧崬鐏╃紒鐘崇叀閺屾洝绠涢弴鐑嗏偓灞剧箾缁楀搫濮傞柡灞界Х椤т線鏌涢幘瀵告噰闁诡喗鍎抽悾锟犲箯閺冣偓濡啫鐣烽妸鈺婃晣闁搞儯鍎辨慨鍌炴煛鐏炵偓绀冪€垫澘瀚板畷鐓庘攽閸粍鍋呮繝鐢靛仜閻°劎鍒掓惔銊ョ；闁规崘鍩栧畷鍙夌箾閹存瑥鐏╂鐐灪娣囧﹪顢涘┑鎰闂佹眹鍔嶉崹鍨潖閾忓湱鐭欐繛鍡樺劤閸撻亶姊虹憴鍕憙妞ゆ泦鍥舵晪闁靛鏅涚粈瀣亜閹惧鈽夊ù婊堢畺閺屻劌鈹戦崱娑扁偓妤€顭胯閸ㄦ娊鍩€椤掑倸浠柛濠冪墪椤啴鎸婃径妯荤稁濠电偛妯婃禍婵嬪磹閻戣姤鐓㈡俊顖欒濡茬鈹戦鑺ョ缂佽鲸鎸婚幏鍛村川婵犲啫鍓垫繝鐢靛仧閵嗗鎹㈠┑鍡欐殾婵炲樊浜滈悞?             */
            if (!StringUtils.hasText(canonicalUrl)) {
                log.warn("discard explicit competitorUrl because canonicalize failed, competitorName={}, sourceType={}, url={}",
                        config.getCompetitorName(), safeSourceType(config.getSourceType()), url);
                rejectedCandidates.add(buildExplicitUrlRejectedCandidate(
                        config,
                        url,
                        EXPLICIT_URL_CANONICALIZE_FAILED,
                        "cannot canonicalize explicit competitorUrl"
                ));
                continue;
            }
            if (!seenCanonicalUrls.add(canonicalUrl)) {
                log.info("discard explicit competitorUrl because canonical URL already exists, competitorName={}, sourceType={}, url={}, canonicalUrl={}",
                        config.getCompetitorName(), safeSourceType(config.getSourceType()), url, canonicalUrl);
                rejectedCandidates.add(buildExplicitUrlRejectedCandidate(
                        config,
                        url,
                        EXPLICIT_URL_DUPLICATE_CANONICAL,
                        "canonicalUrl=" + canonicalUrl
                ));
                continue;
            }
            mergedCandidates.add(buildExplicitConfiguredCandidate(config, url));
        }
        return new InitialCandidateResolution(mergedCandidates, rejectedCandidates);
    }

    private SourceCandidate buildExplicitUrlRejectedCandidate(CollectorNodeConfig config,
                                                              String url,
                                                              String rejectionCode,
                                                              String detail) {
        String selectionReason = StringUtils.hasText(detail)
                ? rejectionCode + ": " + detail
                : rejectionCode;
        return SourceCandidate.builder()
                .url(url)
                .title(config.getCompetitorName() + " - " + safeSourceType(config.getSourceType()) + " entry")
                .sourceType(safeSourceType(config.getSourceType()))
                .discoveryMethod("DIRECT_LOCATOR")
                .providerKey("planned")
                .reason("闂傚倸鍊搁崐鎼佸磹妞嬪海鐭嗗〒姘ｅ亾妤犵偞鐗犻、鏇㈠Χ閸屾矮澹曞┑顔筋焽閸樠冾嚕椤旈敮鍋撶憴鍕婵炲弶鐗犻幃楣冩倻閽樺鍊炲銈庡幗閸ㄥ墎绱?competitorUrls 闂傚倸鍊搁崐鎼佸磹妞嬪海鐭嗗〒姘ｅ亾妤犵偞鐗犻、鏇㈡晜閽樺缃曟繝鐢靛Т閿曘倗鈧凹鍣ｉ妴鍛村蓟閵夛妇鍙嗗┑鐘绘涧濡瑦寰勯崟顓涘亾鐟欏嫭绀€鐎殿喖鐖奸獮鍫ュΩ閵夘喗瀵岄柣鐘叉穿瀵挻绔熼弴銏＄厽閹兼番鍊ゅ鎰箾閸欏鑰块柕鍡楀暞缁绘繈宕樿缁犳岸姊虹紒妯虹伇婵☆偄瀚弫顕€姊绘笟鈧褏鎹㈤幒鎾村弿闁惧浚鍋嗛悵鍫曟煛閸モ晛浜归柡鈧禒瀣厽闁归偊鍓欑痪褎銇勯妷褍浠遍柡宀€鍠栧畷銊︾節閸愩劌鏀柣搴ゎ潐濞叉﹢宕归崸妤冨祦婵せ鍋撶€殿喖鐖奸獮瀣偐閻㈤潧绁﹂梻鍌欐祰椤曆囧礄閻ｅ瞼绀婇柛鈩冪☉缁€鍫熸叏濮楀棗澧婚柛銈呯墛缁绘稑顔忛鑽ゅ嚬闂佹娊鏀遍崹鍧楀蓟閻旇櫣鐭欓柟绋垮瀹曟娊姊虹€圭姵顥夋い锔诲灦閸╃偤骞嬮敃鈧壕鍏肩箾閹寸偟鎲块柟宄邦煼閹宕归锝囧嚒闂佹寧娲︽禍顏堟偘椤曗偓瀹曞崬顫濋崗纰扁偓鍥⒒娴ｅ憡鎲搁柛鐘查叄楠炲﹤顫滈埀顒€顕ｆ繝姘亜闁惧繐婀遍敍婊冣攽閳藉棗鐏ユ繛鍜冪秮閹兘濡搁埡鍌楁嫼闂佸憡绻傜€氼參宕冲ú顏呯厵妞ゆ梻鍘ч埀顒€鐏濋?discarded audit")
                .domain(extractDomain(url))
                .sourceUrls(List.of(url))
                .selectionStage("DISCARDED")
                .selectionReason(selectionReason)
                .selectionSummary("explicit competitorUrls discarded")
                .build();
    }

    private String buildLoadCandidatesSummary(boolean resumedFromCheckpoint,
                                              int candidateCount,
                                              List<SourceCandidate> rejectedCandidates) {
        String baseMessage = (resumedFromCheckpoint ? "checkpoint-loaded " : "loaded ")
                + candidateCount
                + " planned candidates";
        if (rejectedCandidates == null || rejectedCandidates.isEmpty()) {
            return baseMessage;
        }
        List<String> rejectionReasons = rejectedCandidates.stream()
                .map(SourceCandidate::getSelectionReason)
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
        return baseMessage
                + "; explicit competitorUrls discarded="
                + rejectedCandidates.size()
                + " ["
                + String.join(", ", rejectionReasons)
                + "]";
    }

    private SearchExecutionPlan initializePlan(SearchExecutionPlan plan) {
        List<SearchExecutionStep> steps = plan == null || plan.getSteps() == null || plan.getSteps().isEmpty()
                ? defaultSteps()
                : plan.getSteps().stream()
                .map(step -> step.toBuilder()
                        .status(SearchExecutionStep.StepStatus.PENDING)
                        .message(null)
                        .startedAt(null)
                        .completedAt(null)
                        .build())
                .toList();
        return SearchExecutionPlan.builder()
                .stage(plan == null ? "COLLECTOR_SEARCH_AND_COLLECT" : plan.getStage())
                .steps(new ArrayList<>(steps))
                .build();
    }

    /**
     * 闂傚倸鍊搁崐鎼佸磹閻戣姤鍤勯柛顐ｆ礀缁犵娀鏌熼幑鎰靛殭閻熸瑱绠撻幃妤呮晲鎼粹€愁潻闂佹悶鍔嶇换鍫ョ嵁閺嶎灔搴敆閳ь剚淇婇懖鈺冩／闁诡垎浣镐划闂佸搫鏈ú妯兼崲濞戙垺鍊锋い鎺嶈兌瑜板懐绱撻崒娆掑厡濠殿喕鍗冲畷鏇㈠箮閽樺鐤呴梺褰掓？缁€浣虹不閿濆鐓ラ柡鍐ㄦ储閳ь兘鍋撻梺绋款儐閹歌崵绮悢鐓庣劦妞ゆ帒瀚畵渚€鎮楅敐搴℃灍闁稿鍔欓弻娑㈠箛椤掍讲鏋欓梺閫炲苯澧柣鈺婂灠椤繐煤椤忓懎鈧兘鏌﹀Ο渚Ц濠殿喖閰ｅ娲传閵夈儲鐎诲┑鐐点€嬬换婵嬬嵁閸愩剮鐔兼嚒閵堝洨妲囬梻浣规偠閸庮垶宕濇径鎰濠电姵纰嶉埛鎴︽煕濞戞﹫鏀荤痪鍓ф暬閺岋繝宕ㄩ鐐垱闂佺硶鏂侀崑鎾愁渻閵堝棗绗傞柣鎺炲娴滄悂鎮介崨濠勫幘濠电偛妯婃禍娆撳箟妤ｅ啯鐓曢柕濞炬櫇閻ｇ儤顨ラ悙鍙夊闁瑰嘲鎳愰幉鎾礋閳规儳浜鹃柛鎰靛枟閳锋帒銆掑锝呬壕濠电偘鍖犻崶浣告喘椤㈡洟鏁冮埀顒勬偪妤ｅ啯鐓冮柛婵嗗閸ｅ綊鏌ｉ幒鎴犱粵闁靛洤瀚伴獮鎺楀幢濡炴儳顥氶梻鍌欑閹芥粓宕伴幇顒夌劷婵炲棙鎼╅弫瀣煥濠靛棭妲归柛瀣ㄥ姂閺屾盯骞橀崘鑼獓闂佸搫鎳岄崹钘夘潖濞差亜宸濆┑鐘插€歌闂備礁鎽滄慨闈涚暆缁嬭法鏆﹂柨婵嗩槸缁犳盯鏌ｅΔ鈧悧鍐箯濞差亝鈷戦柛锔诲弾閻掔偓绻涚€电鍘撮柛鈹垮劜瀵板嫰骞囬鐘插箺婵＄偑鍊栭幐鑽ゆ崲閸℃稑绀傜€光偓閸曨剛鍘靛Δ鐘靛仜閻忔繈鎮橀懠顑藉亾濞堝灝鏋熼柟姝屾珪閹便劑鍩€椤掑嫭鐓冮柦妯侯槹椤ョ偤鏌嶉柨瀣⒌婵﹦绮粭鐔煎焵椤掑嫬鐒垫い鎺戝€告禒婊堟煠濞茶鐏￠柡鍛板煐鐎佃偐鈧稒顭囬崢鐢告⒑閼测斁鎷￠柛鎾寸懇閵嗗倹绺介崨濠勫幍閻庣懓瀚晶妤呭吹閸ヮ剚鐓欐い鏃€鍎抽崢瀛橆殽?query闂傚倸鍊搁崐鎼佸磹妞嬪孩顐芥慨姗嗗墻閻掔晫鎲稿鍫罕闂備礁鎼崐褰掓晬閺嚶颁汗闁圭儤鍨归崐鐐差渻閵堝懐绠伴柟閿嬪灩濡叉劙骞橀弬銉︽杸闂佺粯锚閻忔岸寮抽埡浣叉斀妞ゆ棁鍋愭晥閻庤娲滈崰鏍€侀弴銏狀潊闁冲搫鍊荤粙鍫ユ⒒閸屾艾鈧娆㈠顒夌劷鐟滃秷鐏嬪┑掳鍊曢崯鐘诲磻閹炬枼妲堥柟鐑樻尰閻濇艾螖閻橀潧浠滄俊顐ｇ箓椤曪綁顢氶埀顒€鐣烽崼鏇炵厸闁告劏鏅滅欢顐︽⒒閸屾瑧顦︽繝鈧柆宥呯；闁圭偓鍓氶悞鑺ョ箾閸℃ɑ鎯勯柡浣稿€归妵鍕冀椤愵澀娌梺鎶芥敱閸ㄥ潡寮婚敐澶婄闁哄啠鍋撴繛鍛缁绘盯宕ㄩ钘夌３闂佸搫鏈惄顖炲箖閳哄懎绠涘ù锝呮贡閺夊綊姊绘担绛嬪殐闁哥姵鎹囧畷鏇㈡偨缁嬫寧鐎梺褰掑亰閸樿偐娆㈤悙娴嬫斀闁绘ɑ褰冮弳鐔兼煃瑜滈崜锕傚礈濮樿鲸宕叉繛鎴欏灩缁狙囨煙鐎涙绠栭柛濠庡灠閳规垿鎮欓幓鎺撳€梺鑽ゅ枂閸庣敻宕洪姀鐙€鍚嬪璺好¤椤法鎹勬笟顖氬壋闂佸憡蓱閹倸顫忓ú顏勫窛濠电姴鍟ˇ鈺呮⒑閸涘﹥灏伴柣鐔叉櫅閻ｅ嘲鈹戠€ｎ€囨煕閵夈垺娅囬柨娑欑矒濮婅櫣鎲撮崟顐ょシ濡炪倖鍨靛ú銊ヮ嚕瑜旈崺鈧い鎺嗗亾閾绘牠鏌ｅ鈧褎绂掗敃鍌涚厱闁哄啠鍋撻柣鐔村劦閹箖鎮滈挊澶岊唺闂佽鎯岄崢浠嬪磽闂堟侗娓婚柕鍫濇缁楀倿鏌ょ€圭姵纭鹃崡杈ㄦ叏濡炶浜惧┑顔硷功缁垶骞忛崨鏉戝窛濠电姴鍟崜鍨繆閻愵亜鈧呪偓闈涚焸瀹曪綁宕橀妸褎娈鹃梺鍝勬储閸ㄦ椽宕甸幒妤佺厪闁割偅绻冮敍宥嗙箾?     * 闂傚倸鍊搁崐鎼佸磹妞嬪海鐭嗗〒姘ｅ亾妤犵偞鐗犻、鏇㈡晝閳ь剛澹曡ぐ鎺撶厱鐟滃酣銆冮崨瀛樺€块柛顭戝亖娴滄粓鏌熼崫鍕ラ柛蹇撶焸閺屾洟宕卞Ο鐑樿癁闂佸搫鑻粔鍫曞箟閹绢喖绀嬫い鎰╁€楅幊鍡涙⒒閸屾碍鎼愭い锔炬暬瀵鏁愰崨鍌滃枔濞戠敻宕担绋课ら梻鍌欑劍閹爼宕愰弽顓熷仭闁靛鏅涢悡姗€鏌熸潏鎯х槣闁轰礁锕弻鐔碱敍閸℃顏ù婊勭墵濮婄粯鎷呴崫銉ㄥ銈冨劜閹歌崵鎹㈠☉銏犵劦妞ゆ帒瀚悡娑樏归敐鍥剁劸闁逞屽墮濞硷繝鐛幇顓犵瘈闁告劑鍔庣粣鐐寸節閻㈤潧孝闁稿妫楄灋闁割偆鍠撶弧鈧紒鐐緲椤﹁京澹曢崸妤佺厱閻庯綆鍋勯悘瀵糕偓瑙勬礃閸旀洟鍩為幋锕€骞㈤柍鍝勫暙婵椽鏌ｉ悢鍝ョ煁婵☆偄鍟撮幃浼搭敊鐠恒劎鏉稿┑鐐村灦椤洭顢橀悡搴富闁靛牆妫欓埛鎺楁煙閾忣偅灏扮紒鍌氱У閵堬綁宕橀埡浣插亾閸偆绠鹃柟瀵稿仧婢ь亪鏌嶈閸撴岸宕归崹顔炬殾闁圭増婢樺洿婵犮垼娉涢敃銊杺闂傚倷绶氶埀顒傚仜閼活垱鏅剁€涙ɑ鍙忓┑鐘插暞閵囨繄鈧娲﹂崑濠傜暦閻旂⒈鏁冮柨婵嗘－閸氭﹢姊婚崒娆掑厡妞ゎ厼鐗嗛～婵嬫晜閻ｅ矈娲稿銈嗗笒鐎氼剛绮婚弻銉︾厪闊洤顑呴埀顒佹礈閻氭儳顓兼径瀣幈濡炪倖鍔戦崐鏇㈠几鎼淬劍鐓曢柟鍨暙濞呭秹鏌″畝瀣ɑ闁诡垱妫冩慨鈧柍鍨涙櫅椤矂姊绘担鐟邦嚋闁煎綊绠栭垾锔炬崉閵婏箑纾梺鎯х箰婢э綀顦归柡宀€鍠栭、娆撴偂鎼粹懣鈺呮⒑鐎圭媭鍤欏Δ鐘虫倐閸┿垺鎯旈妸銉ь吅闂佺粯蓱钃辩紒顔芥尭椤繐煤椤忓拋妫冮梺鍐叉惈閸熸媽鎽梻鍌欒兌閹虫捇骞夐埄鍐濠电姴鍊婚弳锔炬喐閻楀牆绗掔紒鐘虫皑閹茬顭ㄩ崼鐔蜂簵闂侀潧顦弲婊堟偂閺囩喓绠鹃柟瀵稿仧閹冲嫮绱撳鍡楃伌闁哄本绋戣灒闁煎鍊栭崳顔剧磽娴ｄ粙鍝洪柟鐟版搐閻ｇ兘骞掗幋鏃€鐎婚梺鍦劋閸ㄧ數鏁鐐粹拻濞达絽鎲￠幉绋库攽椤旇姤灏﹂柡灞斤躬閺佹劙宕ㄩ娑欘啎闂備胶鎳撴晶搴ㄦ偤閺冨牆鏋佺€广儱妫涚粻楣冩煙鐎电鍓遍柣鎺旀櫕缁辨帡骞囬闂存濠殿喖锕ュ浠嬬嵁閺嶎厽鍊烽柟缁樺笒椤垿姊绘担鍛婂暈闁哄被鍔戣棟闂傚牊绋撻弳锕傛煙鏉堝墽鐣辩紒鐘差煼閹鈽夊▍顓т邯瀹曟繈宕ㄩ娑欐杸闂佺粯顭囩划顖氣槈瑜旈弻锝呂旈埀顒傛崲濡警鍤曢柤鍝ユ暩椤╃兘鎮楅敐搴′簮闁归攱妞藉娲嚒閵堝懏鐎惧┑鐘灪閿曘垽鐛崘顔肩闁挎梻鏅崢閬嶆⒑闂堟稓澧曢柟铏耿瀹曪綁宕熼娑氬幈闂佸疇顫夐崕铏閻愵兛绻嗛柣鎰典簻閳ь剚鐗犲畷褰掑礈娴ｆ彃浜剧紒妤佺☉濞夋岸宕堕浣糕偓濠氭煠閹帒鍔氬ù鐙€鍨跺楦裤亹閹烘垳鍠婇梺鍝ュУ閻楃姴顕ｉ幖浣哥闁绘鏁搁敍婊堟⒑缁嬫寧婀扮紒瀣灥闇夋い鏇楀亾闁哄本鐩崺鐐哄箚瑜屾竟鏇炩攽閿涘嫬浜奸柛濠冪墱閺侇喗绻濋崶銊ユ畱闂佸壊鍋呭ú鏍偂濠靛鐓涢柛銉ｅ劚閻忣亪鏌ｉ幘瀛樼闁靛洤瀚伴獮鍥濞戞﹩娼鹃梻浣筋嚃閸犳盯宕戦幇顔筋潟闁圭儤顨嗛崑鎰版煠绾板崬澧板Δ鏃堟⒒娴ｅ憡鎯堝璺烘喘閸┾偓妞ゆ帊绀佹晶顖炴煕濮橆剦鍎忔い顓℃硶閹瑰嫭绗熼姘闂備礁鎼Λ娑欑箾閳ь剚鎱ㄦ繝鍛仩闁归濞€閸ㄩ箖鎼归銈勬喚婵犵數濮烽弫鎼佸磻濞戙垺鏅濇い蹇撳閺嗭妇鎲搁悧鍫濈瑨缂佺姵姘ㄩ幉鍛婂緞閹邦剛鍝楁繛瀵稿Т椤戝棝鎮￠弴銏＄厸闁搞儺鐓侀鍛箚闁绘挸绡?
     */
    private SearchExecutionPlan enrichExecutionPlan(SearchExecutionPlan executionPlan,
                                                    CollectorNodeConfig config,
                                                    int targetCount,
                                                    int minVerifiedCount) {
        SearchExecutionPlan basePlan = executionPlan == null ? initializePlan(null) : executionPlan;
        return basePlan.toBuilder()
                .searchQueries(resolveSearchQueries(config, basePlan))
                .fallbackOrder(resolveSearchFallbackOrder(config))
                .targetCount(targetCount)
                .minVerifiedCount(minVerifiedCount)
                .build();
    }

    private List<SearchExecutionStep> defaultSteps() {
        return List.of(
                SearchExecutionStep.builder()
                        .stepCode("LOAD_CANDIDATES")
                        .goal("load planned candidates")
                        .expectedDurationMs(500L)
                        .dependency("nodeConfig")
                        .status(SearchExecutionStep.StepStatus.PENDING)
                        .build(),
                SearchExecutionStep.builder()
                        .stepCode("TAVILY_BOOTSTRAP_ENRICH")
                        .goal("run Tavily Phase 1 bootstrap enrichment")
                        .expectedDurationMs(4000L)
                        .dependency("tavily")
                        .status(SearchExecutionStep.StepStatus.PENDING)
                        .build(),
                SearchExecutionStep.builder()
                        .stepCode("CANDIDATE_FUSION_RANK")
                        .goal("run final candidate fusion and ranking")
                        .expectedDurationMs(800L)
                        .dependency("ranker")
                        .status(SearchExecutionStep.StepStatus.PENDING)
                        .build(),
                SearchExecutionStep.builder()
                        .stepCode("VERIFY_TOP_CANDIDATES")
                        .goal("verify high-priority candidates")
                        .expectedDurationMs(5000L)
                        .dependency("browser")
                        .status(SearchExecutionStep.StepStatus.PENDING)
                        .build(),
                SearchExecutionStep.builder()
                        .stepCode("BROWSER_SUPPLEMENT_SEARCH")
                        .goal("run browser supplement search when needed")
                        .expectedDurationMs(8000L)
                        .dependency("searchProvider")
                        .status(SearchExecutionStep.StepStatus.PENDING)
                        .build(),
                SearchExecutionStep.builder()
                        .stepCode("PUBLIC_EVIDENCE_RECOVERY")
                        .goal("recover public evidence candidates when needed")
                        .expectedDurationMs(3000L)
                        .dependency("candidateVerifier")
                        .status(SearchExecutionStep.StepStatus.PENDING)
                        .build(),
                SearchExecutionStep.builder()
                        .stepCode("SELECT_TARGETS")
                        .goal("select final collection targets")
                        .expectedDurationMs(1000L)
                        .dependency("ranker")
                        .status(SearchExecutionStep.StepStatus.PENDING)
                        .build(),
                SearchExecutionStep.builder()
                        .stepCode("COLLECT_PAGES")
                        .goal("collect selected pages and persist evidence")
                        .expectedDurationMs(12000L)
                        .dependency("collector")
                        .status(SearchExecutionStep.StepStatus.PENDING)
                .build()
        );
    }

    /**
     * 闂傚倸鍊搁崐椋庣矆娓氣偓楠炴牠顢曚綅閸ヮ剦鏁冮柨鏇楀亾闁汇倗鍋撶换婵囩節閸屾粌顣虹紓浣插亾濠㈣泛顑嗛崣蹇斾繆閻愰鍤欏ù婊堢畺濮婃椽妫冨☉娆樻缂備浇鍩栧畝鎼佹偘椤旈敮鍋撻敐搴℃灍闁哄懏绻堥弻宥堫檨闁告挻鐩崺鈧い鎺嶆祰婢规ɑ銇勯敂鐐毈鐎殿喖顭烽弫鎰緞婵犲喚妫熼梻浣稿閻撳牓宕板Δ鍜佹晩闁瑰鍋熺弧鈧梺闈涢獜缁插墽娑垫ィ鍐╁殞鐎光偓閸曨剛鍘搁梺閫炲苯澧撮柡浣稿暣瀹曟帒鈽夊顒€绠ラ梻鍌氬€风欢锟犲矗韫囨洜纾芥慨妯跨簿婵啿霉閻樺樊鍎愰柣鎾跺枛閺岋綁寮幐搴㈠枑缂備胶濮烽崑銈夊蓟閿涘嫪娌柛鎾楀嫬鍨辨俊銈囧Х閸嬫稑煤椤擃潿鈧礁螖閸涱厾锛滈梺绋挎湰閿氶柍褜鍓氱换鍫濐嚕鐠囧樊鍚嬪璺好￠妸鈺傜叆闁哄啠鍋撻柛搴㈠▕閸╂盯宕奸姀銏紳婵炶揪绲肩划娆撳传濞差亝鐓欓柣鐔哄閹兼劖銇勯弴顏嗙ɑ缂佸倹甯為埀顒婄到閻忔岸寮插┑瀣拺闂傚牊绋撴晶鏇㈡煙閾忣偄濮堢紒鍌涘浮椤㈡盯鎮欑划瑙勫濠电偠鎻徊浠嬪箹椤愶絿顩锋繛宸簼閻撴洟骞栧ǎ顒€鈧洟鎯冨ú顏呯厱闁圭儤鎸哥粭鎺撱亜閹剧偨鍋㈢€规洖鐖奸崺锟犲礃椤忓嫬歇闂傚倸鍊烽懗鍫曞储瑜旈敐鐐哄即閵忕姷锛欓梺鍝勭▉閸樿偐绮ｅΔ鍛厱闁斥晛鍟伴埊鏇㈡煟閹惧瓨绀冮柕鍥у楠炲洭宕滄担鐟颁还缂傚倷鑳舵慨鐢告偋閻樿钃熼柨婵嗘噳閺€浠嬫煕閺囥劌浜愰柛瀣崌瀵挳濮€閿涘嫮鏆㈤梻鍌氬€烽懗鍫曗€﹂崼銏″床闁瑰濮烽惌鍫㈡喐閻楀牆淇柡浣稿€块弻娑㈩敃閿濆洨鐣甸梺绋款儏缁夋挳鍩為幋锔藉€烽柡澶嬪灩娴犙囨⒑閹肩偛濡肩紓宥咃躬楠炲啫顫滈埀顒勫箹瑜版帩鏁冮柕鍫濇川濡插洭姊绘担绋款棌闁绘挸鐗撳畷鎴﹀川椤栨瑧鍓ㄩ梺姹囧灮閺佸摜澹曟總鍛婄厓鐟滄粓宕滃顒夊殫闁告洦鍨扮粻娑欍亜閹捐泛孝妤犵偞锕㈠缁樻媴閸涘﹥鍎撳銈忛檮婢瑰棝鍩€椤掑倻鎳楅柛鎰劵閳ь剙娼￠弻锝呂旈埀顒勬偋閸涱垱宕查柛鈩冪⊕閻撴瑧绱撴担濮戭亞绮鑸电厱闁绘柨鎼禒褏绱掓潏銊ョ闁逞屽墾缂嶅棙绂嶇捄浣曠喖鍩€椤掍胶绡€缁炬澘顦辩壕鍧楁煕韫囨棑鑰跨€殿噮鍋婂畷鎺楁倷閼碱剛鏆伴柣鐔哥矋缁矁鐏嬮梺鍝勫暙閻楀﹪鍩涢幋锔藉仯闁搞儺浜滈惃铏圭磼閻樺啿顥嬬紒杈ㄥ笧缁辨帒螣閸濆嫷娼氶梻浣芥〃缁€渚€宕幘顔衡偓渚€寮崼婵嬪敹濠电娀娼ч鍡涙晬濡ゅ懏鈷掑ù锝呮憸缁夌儤淇婇銏″仴闁诡喚鍋ら弫鍐磼濮橆剚鍎柣搴＄畭閸庨亶藝娴煎瓨鍋傞柡鍥ュ灪閸婂爼鏌ｉ幇顓炵祷闁抽攱妫冮弻锝夘敇閻戝洤浼愰梻鍥ь樀閺岋絽鈻庣仦鎴掑闂備焦鎮堕崝蹇撯枖濞戭澁缍栭煫鍥ㄦ媼濞差亶鏁傞柛鏇ㄥ弮閻涙捇姊洪懡銈呅ｅù婊€绮欏畷婊堟焼瀹ュ憘褍顭跨捄渚剳闁告ü绮欏娲濞戞艾顣洪梺鐟板暱缁绘妫㈤梺缁樺姉閺佸摜澹曟總鍛婂€甸柨婵嗙凹閹茬偓淇婇妤€浜惧┑锛勫亼閸婃垿宕瑰ú顏呮櫇闁靛繈鍊曠粻鏍煃鏉炴媽鍏岄柡鍡畵閺屾盯濡烽敐鍛瀴濡?fallback 濠电姷鏁告慨鐑姐€傞鐐潟闁哄洢鍨圭壕濠氭煙鏉堝墽鐣辩痪鎯х秺閺岋繝宕堕妷銉т患缂備胶濮鹃～澶愬Φ閸曨垰绠涢柍杞拌閸嬫挸鈽夊▎鎰伎闂佹儳娴氶崑浣圭濠婂牊鐓欓柟顖嗗啳鍩為梺璇茬箚閺呮粌顭?
     * 闂傚倸鍊搁崐椋庣矆娓氣偓楠炴牠顢曚綅閸ヮ剦鏁冮柨鏇楀亾闁汇倗鍋撶换婵囩節閸屾粌顤€闂佺顑戠换婵嬪蓟閵娾晛鍗抽柣鎰ゴ閸嬫捁銇愰幒鎴狅紱闁诲函缍嗛崰妤呭煕閹寸偑浜滈柟鍝勬娴滃墽绱撴担鍓叉Ц妞ゆ洦鍙冮獮澶愬箻椤旇姤娅嗛梻浣诡儥閸ㄧ増绂嶆ィ鍐╃厱闊洦鎼╁Σ绋棵瑰鍫㈢暫婵﹥妞介弻鍛存倷閼艰泛顏繝鈷€鍌氬祮闁哄瞼鍠栭、姘跺川椤撶喓褰庢俊鐐€戦崹鍝劽洪悢鐓庢瀬闁稿瞼鍋炵€电姴顭跨捄鐚村伐妞ゃ儲锕㈠濠氬磼濞嗘埈妲梺鍦拡閸嬪﹤鐣烽鐑嗘晝闁挎洍鍋撶痪鎯х秺閺岋綁濮€閵忊晝鍔搁梺鍝勵儎缁舵岸寮诲鍫闂佸憡鎸诲銊╁箲閵忕姭妲堟慨姗堢到娴滈箖鏌ｉ姀鈽嗗晱闁绘帡绠栧Λ鍕偓锝庝憾濞撳鏌曢崼婵囶棡闁艰尙濞€閺屾盯寮撮妸銉ヮ潻閻庢鍠栧鈥愁潖閾忓湱纾兼俊顖滃劦閹峰姊洪崨濠庣劶闁搞儯鍔岄崜鐟扳攽鎺抽崐鎾绘⒔瀹ュ棛顩叉繝濠傜墛閻撴稓鈧箍鍎遍崯顐ｄ繆閸ф鐓欓柛蹇撴嚀閸忓瞼绱掓潏銊ユ诞濠碘剝鎮傞弫鍐焵椤掑媻澶屸偓锝庡墰绾惧ジ寮堕崼娑樺缂佹う鍥ㄧ厓鐟滄粓宕滈妸褏绀婇柛鈩冾焽椤╁弶绻濇繝鍌滃婵鐓″娲敆閳ь剛绮旈悽鍛婂亗闁绘柨鍚嬮悡銉︾節闂堟稒顥為柟鍏煎姇闇夋繝濠傚暙閳锋棃鏌嶈閸撴瑩鎮樺顒夌唵婵☆垰鐨烽崑鎾愁潩閻撳骸鈷嬪銈冨灪钃辩紒铏规櫕缁瑧鎹勯妸銉㈠亾閻愮儤鍋℃繝濠傚暟缁犲鏌熼鍏煎仴闁糕斁鍋撳銈嗗笒鐎氼參鍩涢幒鎳ㄥ綊鏁愰崨顔藉創闂佸憡妫戠粻鎾诲蓟閿熺姴宸濇い鏂垮⒔閻ゅ嫰姊洪悙钘夊姷缂佺姵鎸搁悾閿嬬附缁嬭銊╂煏婢诡垰鎳忓В鍥⒒閸屾瑨鍏岄柟铏尰閺呭爼鎮剧仦钘夌亰濡炪倖鐗滈崑娑氬鐠恒劉鍋撻獮鍨姎妞わ缚鍗抽幃锟犲即閵忥紕鍘搁梺鎼炲劘閸庤鲸淇婃總鍛婄厸闁糕剝顨忓Σ鍛娿亜椤撯剝纭堕柟鐟板瀹曪絽鐣￠幍顔尖叺閻庢鍠撻崝鎴﹀极閸愵喖纾兼繛鎴炶壘楠炲秹姊婚崒娆戣窗闁告瑥绻掔划濠氬箣閿曗偓閸戠娀鏌曢崼婵愭Ч闁绘挻鐟╅弻銈夊箒閹烘垵濮㈤梺娲诲幗鐢繝寮婚垾宕囨殕閻庯綆鍓涜ⅵ闂備浇妗ㄩ悞锕傚礉濞嗗繒鏆︽慨妞诲亾濠碘剝鎮傛俊鐑藉Ψ閹扳晛鍔ょ紒杈ㄦ崌瀹曟帒鈻庨幇顔哄仒濠碉紕鍋炲娆撳箺濠婂牆绠查柕蹇嬪€曢柋鍥煏婢跺牆鍔ラ柟鑺ユ礋濮婅櫣鍖栭弴鐐测拤缂備礁顑嗛崹鍨暦瑜版帒纾兼繛鎴炵墧缁ㄥ姊洪崫鍕殭闁稿﹤鎽滈弫顕€宕奸弴鐔蜂化閻熸粌绉归、鏍幢濡皷鏀虫繝鐢靛Т濞诧箓宕愰柨瀣ㄤ簻闊洦鎸搁銈夋煕鐎ｎ偅宕岀€殿喗鎸虫慨鈧柣妯活問閸熷洦淇婇悙顏勨偓鏍礉閹达箑纾规繛鎴炵懄閸欏繘鏌ц箛锝呬簴濞存粍绮撻弻鐔煎箲閹邦厾銆愰梺鍝勵儏濞撮妲愰幒妤€绀堝ù锝夋櫜濡叉劙鎮楀▓鍨灈闁绘牜鍘ч悾鐑芥偂鎼存ɑ顫嶅┑鈽嗗灟鐠€锕傛倵閸愭祴鏀介柣鎰▕閸ょ喎鈹戦鐐毈闁轰礁鍟存慨鈧柕鍫濇嚀閹?
     */
    private SupplementExecutionOutcome executeSupplementByFallbackOrder(CollectorNodeConfig config,
                                                                        List<SourceCandidate> existingCandidates,
                                                                        int targetPoolSize,
                                                                        ResolvedFieldEvidenceQueryPlan fieldEvidenceQueryPlan,
                                                                        Long fieldEvidenceExecutionDeadlineEpochMillis) {
        BrowserSearchRuntimeResult browserSearchResult = defaultBrowserSupplementResult(config);
        List<SourceCandidate> supplementedCandidates = new ArrayList<>();
        boolean providerFallbackUsed = false;
        String supplementMethod = "NONE";
        String fallbackDecision = "USE_PLANNED_CANDIDATES";
        boolean browserModeEnabled = Boolean.TRUE.equals(config.getBrowserSearchEnabled())
                && !"HTTP_ONLY".equalsIgnoreCase(config.getSearchMode());
        boolean httpModeEnabled = !"BROWSER_ONLY".equalsIgnoreCase(config.getSearchMode())
                && !"HEURISTIC_ONLY".equalsIgnoreCase(config.getSearchMode());
        boolean browserExecuted = false;
        boolean httpExecuted = false;
        SearchSourceRequest sourceRequest = null;
        boolean pendingFieldEvidenceQueries = hasPendingFieldEvidenceQueries(config);

        for (String stage : resolveSearchFallbackOrder(config)) {
            if (existingCandidates.size() + supplementedCandidates.size() >= targetPoolSize
                    && !pendingFieldEvidenceQueries) {
                break;
            }

            if ("BROWSER".equals(stage) && browserModeEnabled && !browserExecuted) {
                browserSearchResult = browserSearchRuntimeService.search(config);
                browserExecuted = true;
                List<SourceCandidate> browserCandidates = removeExistingCandidates(
                        concat(
                                normalizeCandidates(browserSearchResult.getCandidates(), "BROWSER", config),
                                expandSearchCandidatesThroughDirectDiscovery(
                                        config,
                                        browserSearchResult.getCandidates(),
                                        concat(existingCandidates, supplementedCandidates)
                                )
                        ),
                        concat(existingCandidates, supplementedCandidates)
                );
                if (!browserCandidates.isEmpty()) {
                    supplementedCandidates.addAll(browserCandidates);
                    supplementMethod = "BROWSER";
                    fallbackDecision = "USE_BROWSER_SUPPLEMENT";
                }
                continue;
            }

            if ("HTTP".equals(stage) && httpModeEnabled && !httpExecuted) {
                sourceRequest = buildSearchSourceRequest(
                        config,
                        existingCandidates,
                        fieldEvidenceQueryPlan,
                        fieldEvidenceExecutionDeadlineEpochMillis
                );
                List<SourceCandidate> httpSearchCandidates = searchSourceProvider.search(sourceRequest);
                if (httpSearchCandidates == null || httpSearchCandidates.isEmpty()) {
                    httpSearchCandidates = searchSourceProvider.search(
                            config.getCompetitorName(),
                            List.of(config.getSourceType())
                    );
                }
                List<SourceCandidate> httpCandidates = removeExistingCandidates(
                        concat(
                                normalizeCandidates(httpSearchCandidates, "HTTP", config),
                                expandSearchCandidatesThroughDirectDiscovery(
                                        config,
                                        httpSearchCandidates,
                                        concat(existingCandidates, supplementedCandidates)
                                )
                        ),
                        concat(existingCandidates, supplementedCandidates)
                );
                httpExecuted = true;
                if (!httpCandidates.isEmpty()) {
                    supplementedCandidates.addAll(httpCandidates);
                    providerFallbackUsed = true;
                    supplementMethod = "HTTP_FALLBACK";
                    fallbackDecision = browserModeEnabled ? "USE_HTTP_FALLBACK" : "BROWSER_DISABLED_USE_HTTP_FALLBACK";
                    if (pendingFieldEvidenceQueries) {
                        break;
                    }
                }
            }
        }

        if (supplementedCandidates.isEmpty()) {
            fallbackDecision = resolveEmptySupplementDecision(browserModeEnabled, httpModeEnabled, browserExecuted, httpExecuted);
        }

        return new SupplementExecutionOutcome(browserSearchResult,
                supplementedCandidates,
                supplementMethod,
                fallbackDecision,
                providerFallbackUsed,
                sourceRequest == null ? null : sourceRequest.getTavilyFastLaneAudit());
    }

    /**
     * 闂傚倸鍊搁崐宄懊归崶褏鏆﹂柣銏㈩焾缁愭鏌熼幍顔碱暭闁稿绻濋弻鏇熺珶椤栨浜鹃梺绋款儐閹告悂锝炲┑瀣亗閹肩补妾ч幏顐︽煟鎼淬値娼愭繛鍙夌矒楠炲﹪骞樼拠鑼幋闂佺懓顕慨顓㈠磻閹剧粯鏅查幖瀛樼箘閻╁酣姊虹紒妯肩畺闁挎洏鍨归～蹇涙惞閸︻厾锛滃┑鈽嗗灥瀹曠敻宕ｉ崱娑欌拺婵懓娲ゆ俊鐣岀磼鐠囪尙澧曢柣锝囧厴楠炲鏁冮埀顒傜不婵犳碍鐓涢柛灞久崝婊堟煟鎼粹槅鐓兼慨濠冩そ瀹曠兘顢橀埄鍐锯偓妤呮⒑閹肩偛濡垮褎顨堢划瀣吋婢跺﹦鐣鹃悷婊勭矒閹垽宕卞☉娆忎化闂佹儳绻掗幊鎾绘儍閹寸姭鍋撶憴鍕仩闁稿海鏁婚獮鍐╃鐎ｅ灚鏅┑鈽嗗灠閹碱偅鎱ㄩ姀銏㈢＝濞达絼绮欓崫娲煙缁嬫鐓兼鐐茬箻瀹曘劑寮堕幋婊呯倞闂備礁鎲″ú锕傚礈濞戙垹鐒垫い鎺戝暞椤忕姷绱掓潏銊﹀鞍闁瑰嘲鎳愰幉鎾礋閵婏箑顏搁梻鍌欑濠€閬嶁€﹂崼銉ョ柈闁秆勵殢閺佸鏌ㄥ┑鍡橆棤妞も晝鍏橀幃妤呮晲鎼存繄鏁栭梺绋匡功閸嬫盯鈥旈崘顔嘉ч柛娑卞灣椤斿洨绱撴担鍓叉Ч闁圭鍟块锝夘敃閵堝棗鏋傞梺鍛婃处閸橀箖顢?PLANNED闂傚倸鍊搁崐鎼佸磹閻戣姤鍊块柨鏃堟暜閸嬫挾绮☉妯诲櫧闁活厽鐟╅弻鐔告綇妤ｅ啯顎嶉梺绋垮椤ㄥ懘婀侀梺鎸庣箓濞层倝宕濈€ｎ兘鍋撶憴鍕閻忓繑鐟╅崺鈧い鎺嗗亾缂佺姴绉瑰畷鏇㈠础閻忕粯妞介弫鍐磼濮樻唻绱辨繝娈垮枟閵囨盯宕戦幘缁樼厵妞ゆ梹鏋婚懓鍧楁煙椤旂晫鎳囨俊顐㈠暙閳藉螖閳ь剟藟濮樿埖鈷掑ù锝堟閵嗗﹪鏌￠崨顔炬创鐎规洘鍔欓獮鏍ㄦ媴濞村浜鹃柨鏇炲€归崵鍕亜閺嶇數绋婚棄瀣⒒閸屾瑨鍏屾い顓炵墦椤㈡牠宕堕妸锕€寮块梺璇″灱閻忔稑鈽夐姀鐙€娼婇梺闈涚墕濡矂骞忛搹鍦＝濞达絽澹婇崕蹇涙倶韫囨挻鍤囩€殿喓鍔嶇换婵嗩潩椤撶姴骞楅梻浣虹帛閺屻劌顕ｇ捄琛℃瀺濠电姴娲﹂悡鏇㈡煃鐟欏嫬鍔ゅù婊呭亾娣囧﹪鎮欓鍕ㄥ亾閺嵮屽晠濠电姵鑹剧壕濠氭煙閻愵剛鏆樺ù婊勭矒閺屻劑寮村顓ф▊闂佸憡鍑归崑濠囧蓟濞戙垹绠抽柟鎹愭珪鐠囩偤姊虹拠鈥虫灍缂侇喖鐭侀悘鎺楁煟韫囨挾绠查柣妤侇殜閹﹢宕橀瑙ｆ嫽婵炴挻鍩冮崑鎾绘煃瑜滈崜娑㈠磻濞戙垺鍤愭い鏍ㄧ⊕濞呯娀鎮楅悽鐢点€婇柛瀣尭閳绘捇宕归鐣屼簽缂傚倷绶￠崰妤呮偡閵夆晜鍋╅柣鎴ｆ闁卞洭鏌￠崶銉ュ闁哄懏绮撻幃妤呯嵁閸喖濮庨梺鐟板暱缁绘ê顕ｉ幎绛嬫晜闁告洏鍔嶉弬鈧?SUPPLEMENTED闂?
     * 闂傚倸鍊搁崐椋庣矆娓氣偓楠炴牠顢曚綅閸ヮ剦鏁冮柨鏇楀亾闁汇倗鍋撶换婵囩節閸屾粌顤€闂佺顑戠换婵嬪蓟閵娾晛鍗抽柣鎰ゴ閸嬫捁銇愰幒鎴狅紱闁诲函缍嗛崰妤呭煕閹寸偑浜滈柟鍝勬娴滃墽绱撴担鍓叉Ц妞ゆ洦鍘鹃崚鎺楀醇閵夈儵鍞堕梺鍐茬亪閺呮稒绂嶉悙顒夋闁绘劘灏欐禒銏ゆ煕閺冣偓绾板秶鎹㈠☉銏犵骇闁瑰瓨绺鹃崑鎾诲捶椤撴稑浜鹃梻鍫熺◤閸嬨垻鈧娲樼敮锟犲箖濞嗘垟鍋撻悽娈跨劸妤犵偛鐗婄换婵嬫偨闂堟刀銏ゆ煥閺囨ê鈧繂鐣烽鐑嗘晬婵ɑ宕橀埀顒€娼￠弻锝呂旈埀顒勬偋閸涱垱宕查柛鈩兦滄禍婊堟煙闁箑鏋涢柡瀣灦缁绘盯宕ㄩ鐘测叡缂備浇椴哥敮锟犲箖閳轰胶鏆﹂柛銉稻椤秹姊洪崜褏甯涢柣妤冨█瀵顓奸崼顐ｎ€囬梻浣告啞閹搁箖宕伴弽顓犲祦闁糕剝绋戦悙濠囨煏婵炲灝鍔撮柛銈冨€濋弻锝嗘償閵忊懇濮囧銈庡幖濞差厼鐣峰┑瀣濞达絽鍘滈幏铏圭磽娴ｅ壊鍎忛悘蹇撴嚇瀹曟繈鎮㈤崗鑲╁幈闂佺粯蓱閻擄繝宕ｉ崟顓涘亾鐟欏嫭纾搁柛銊ょ矙閻涱喖顫滈埀顒勩€佸▎鎾村仼閻忕偠妫勭粻娲⒒閸屾瑧顦﹂柣銈呮搐椤╁ジ濡搁埡鍌氭畱闂佸壊鍋呭ú鏍不濞差亝鐓熸俊顖濆亹鐢盯鏌涚€ｃ劌濡介柕鍥у瀵粙濡歌婵洭鏌ｈ箛鎾荤崪缂佺姵鎹囧濠氭偄绾拌鲸鏅┑顔筋焾娴滎剟宕濋娑氱瘈缁炬澘顦辩壕鍧楁煕韫囨棑鑰跨€规洘妞介弫鎾绘偐閼碱剙濮︽俊鐐€栫敮濠囨嚄閸洘鍋熼柛顐ｆ礃閻撴盯鏌涢妷锝呭姎闁诲浚浜弻锝夊箻鐎涙顦ラ梺瀹狀潐閸ㄥ潡骞冮埡鍛闁圭儤鎸婚宥夋⒒娴ｇ儤鍤€闁哥喎鐏濈叅妞ゆ挶鍨归拑鐔衡偓骞垮劚閻楁粌顬婇妸鈺傗拺闁告稑锕ョ亸浼存煟閻斿弶娅堟俊顐ゅ枛濮婃椽宕崟鍨梺璺ㄥ枂閸庣敻宕洪埀顒併亜閹哄秶顦︽繛鎼枤閳ь剝顫夊ú鏍Χ缁嬫鍤曢柟缁㈠枟閸婇攱绻涢崼鐔奉嚋鐎涙繃绻濋悽闈浶ラ柡浣告啞缁绘盯鍩€椤掍胶绠惧ù锝呭暱閸樻儳煤椤忓懏娅囬梺绋挎湰缁嬪牓骞愰崘顔藉€垫鐐茬仢閸旀碍銇勯敂鍨祮闁诡噯绻濋幃鈺伱圭€ｎ偅鏉搁梻浣侯焾閺堫剟宕欓悷鎷旓絾顦版惔锝囷紲闂侀潧顭堥崕娲偂婵傚憡鐓涚€光偓閳ь剟宕伴幇鏉跨疄闁靛ň鏅涚粻娑欍亜閹烘垵鈧兘鎯€椤忓棛纾介柛灞剧懅椤︼附銇勯幋婵囧殗閽樻繈鏌ｉ姀鈩冨仩闁逞屽厸缁舵艾顕ｉ鈧畷鐓庘攽鐎ｎ亝鏆梻鍌欒兌缁垶寮婚妸銉殨闁割偅娲橀崐鐢稿级閸稑濡跨紒鈾€鍋撻梻浣圭湽閸ㄨ棄顭囪缁傛帒顭ㄩ崼鐔哄幗濠德板€愰崑鎾剁磼缂佹◤顏堟偩閻戣棄绠ｉ柨鏃囧Г濞呮粍绻濋姀锝嗙【闁挎洩绠撳畷?
     */
    private List<SourceCandidate> normalizeCandidates(List<SourceCandidate> candidates,
                                                      String stage,
                                                      CollectorNodeConfig config) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        String sourceFamilyKey = searchPolicyResolver.resolveSourceFamilyKeyForSourceType(config.getSourceType());
        String sourceFamilyRole = searchPolicyResolver.resolveSourceFamilyRole(sourceFamilyKey).name();
        List<SourceCandidate> normalized = candidates.stream()
                .filter(candidate -> candidate != null && StringUtils.hasText(candidate.getUrl()))
                .map(candidate -> {
                    SourceCandidate base = normalizeCandidateCanonicalUrl(sourceCandidateRanker.ensureScores(candidate));
                    if (base == null) {
                        return null;
                    }
                    String providerKey = resolveProviderKey(base, stage);
                    String effectiveStage = resolveSelectionStage(base, stage);
                    String effectiveReason = StringUtils.hasText(base.getSelectionReason())
                            ? base.getSelectionReason()
                            : ("PLANNED".equals(stage) ? "planned candidate retained for search execution" : "candidate retained after runtime supplementation");
                    return base.toBuilder()
                            .sourceFamilyKey(StringUtils.hasText(base.getSourceFamilyKey())
                                    ? base.getSourceFamilyKey()
                                    : sourceFamilyKey)
                            .sourceFamilyRole(StringUtils.hasText(base.getSourceFamilyRole())
                                    ? base.getSourceFamilyRole()
                                    : sourceFamilyRole)
                            .providerKey(providerKey)
                            .providerRole(searchPolicyResolver.resolveProviderRole(providerKey).name())
                            .sourceUrls((base.getSourceUrls() == null || base.getSourceUrls().isEmpty())
                                    ? List.of(base.getUrl())
                                    : base.getSourceUrls())
                            .selectionStage(effectiveStage)
                            .selectionReason(effectiveReason)
                            .build();
                })
                .filter(java.util.Objects::nonNull)
                .filter(candidate -> !isBlockedDomain(candidate, config.getBlockedDomains()))
                .toList();
        return sourceCandidateRanker.rankAndDeduplicate(normalized);
    }

    /**
     * Tavily/bootstrap 缂傚倸鍊搁崐鎼佸磹閹间礁纾归柣鎴ｅГ閸婂潡鏌ㄩ弬鍨挃闁活厽鐟╅弻鐔封枎闄囬褍煤閵堝鍋╅柣鎴ｆ閻愬﹪鏌曟繛鍨姎缂併劊鍎甸弻锝嗘償閿涘嫮鏆涢梺绋块瀹曨剛鍙呴梺鎸庢礀閸婂摜澹曠紒妯诲弿婵＄偠顕ф禍楣冩⒑鐠団€虫灍闁挎洏鍨介獮鍐ㄢ枎閹炬惌妫冨┑鐐村灦椤ㄥ懘藟濮樿埖鈷掑ù锝堟閵嗗﹪鏌￠崨顔炬创鐎规洘鍔欏畷绋课旈埀顒勬嫅閻斿摜绠鹃柟瀵稿仧閹冲懐绱掗埦鈧崑鎾绘⒒娴ｅ憡鍟為柛鏃撻檮缁傚秴鈹戠€ｎ亪妫锋繛瀵稿帶閻°劑鍩涢幋锔解拺妞ゆ劑鍊曟禒婊堟煠濞茶鐏￠柡鍛埣椤㈡瑦鎱ㄩ幇顏嗙泿闂備焦瀵уΛ渚€顢氳閹﹢鎮╃紒妯煎弳?provider 闂傚倸鍊搁崐宄懊归崶顒夋晪鐟滃繘骞戦姀銈呯婵°倐鍋撶痪鍓х帛缁绘盯骞嬪▎蹇曚患缂佺偓宕橀～澶愬箞閵娿儮鏀介柛鈩冨嚬濞艰埖绻涚€涙鐭婄紓宥咃躬瀵鏁嶉崟顏呭媰闁荤姴娲﹁ぐ鍐╂叏閺囥垺鈷戦柟鑲╁仜閳ь剚娲滈埀顒佺煯閸楀啿顕ｆ繝姘櫇闁逞屽墲閻忔帗绻涢幘鏉戝毈闁搞劏浜悷褔姊绘担鍦菇闁糕晛瀚板畷褰掝敆閸曨偆锛熼梻渚囧墮缁夌數绮?PLANNED 闂傚倸鍊峰ù鍥х暦閻㈢绐楅柟閭﹀枛閸ㄦ繈鐓崶銊р槈闁哄嫨鍎甸弻娑㈠Ψ椤旂厧顫╅梺鎼炲妼閸婂湱鎹㈠☉姗嗗晠妞ゆ棁宕甸惄搴ㄦ⒑缂佹ê绗掗柣蹇斿哺婵＄敻宕熼姘鳖吅闂佹寧绻傚Λ娑㈠Υ婵犲偆娓婚柕鍫濈箺椤撹櫣绱掗悩铏磳鐎殿喛顕ч埥澶愬閻樻爠鍥х缂侇喖鍘滈崑鎾崇暦閸モ晜缍侀梻浣筋嚙濮橈箓锝炴径濞掗缚绠涘☉妯虹€梺鑺ッˇ閬嶅汲閿曞倹鐓欓柣鎴烇供濞堟洟鏌￠崨顔剧煉闁哄本鐩獮妯何旈埀顒勫嫉椤掆偓閺?     * 闂傚倸鍊搁崐椋庣矆娓氣偓楠炴牠顢曚綅閸ヮ剦鏁冮柨鏇楀亾闁汇倗鍋撶换婵囩節閸屾粌顤€闂佺顑戠换婵嬪蓟瀹ュ浼犻柛鏇ㄥ墮濞咃綁姊婚崒姘簽闁搞劌鐖煎濠氭晲婢跺á褔骞栨潏鍓х？濞寸媭鍨跺鍝勑ч崶褉鍋撻幇鏉跨；闁瑰墽绮埛鎺懨归敐鍛暈闁哥喓鍋熺槐鎺斺偓锝庡亜濞搭噣鏌熼鈧粻鏍€侀弮鍫濋唶婵犻潧鐗嗘慨锔戒繆閻愵亜鈧牕顔忔繝姘；闁规儳澧庣壕鑲╃磽娴ｇ櫢鍏柤鏉挎健閺岋絽鈽夐崡鐐寸彎濡ょ姷鍋涘ú顓€佸鈧幃娆撴偨閻㈤潧绁﹂梻鍌欐祰椤曆呮崲閹烘纾婚柣鎰惈绾惧潡鏌熼幆鏉啃撻柛濠呮硶缁辨帞鈧綆浜炲銊╂煛閳ь剟鎳為妷锝勭盎闂佸搫鍟崐鐟扳枍閺囩姷纾奸柣妯哄暱椤ュ绱掓潏銊﹀碍妞ゆ挸鍚嬮幏鍛存⒐閹邦剦妫滅紓鍌氬€搁崐鎼佸磹閻戣姤鍤勯柛鎾茬閸ㄦ繃銇勯弽顐沪闁哄懏绻堥弻鏇＄疀閺囩倫銉╂煛閸☆參妾ǎ鍥э躬婵″爼宕ㄩ鍏碱仩闂佸摜鍎愰崹鍫曞箖瀹勯偊鐓ラ柛鏇ㄥ墻濡啴鎮楃憴鍕妞ゃ劌锕ら悾鐑藉醇閺囩偟鍘搁梺鍛婂姦娴滈妲愰崘娴嬫斀闁绘劘鍩栬ぐ褏绱掗煫顓犵煓妤犵偞鐗犻、鏇㈡晝閳ь剟宕欓悩缁樼厸闁告劧绲芥禍鍓х磽娴ｈ櫣甯涢柣鈺婂灠閻ｉ攱绺介崨濠備簻婵＄偛顑呯花鑲╂濠靛洨绡€婵炲牆鐏濋弸娑㈡煥閺囨ê鈧繃淇婇崼鏇炵濞达絽鎽滈悿鍥р攽閻樿宸ラ柛鐔哄█瀵悂寮崼鐔哄幈濡炪値鍘介崹鐢稿几閻斿吋鐓涘璺侯儐閸婃劖鎱ㄦ繝鍐┿仢闁圭绻濇俊鍫曞川椤旈敮鍋撻幆褉鏀介柣鎰絻缁狙囨煟濡や焦绀夐柣蹇擃儏閳规垶骞婇柛濠冩礋楠炲﹥鎯旈妸锕€浠惧銈嗘磵閸嬫捇鏌″畝鈧崰鏍嵁閹达箑绠涢梻鍫熺⊕椤斿嫮绱?BOOTSTRAPPED / SUPPLEMENTED闂?     * 闂傚倸鍊搁崐鎼佸磹妞嬪海鐭嗗〒姘ｅ亾妤犵偛顦甸弫鎾绘偐閸愯弓绨绘俊鐐€栫敮鎺楀磹缂佹鈻旂€广儱顦伴悡娆撳级閸繂鈷旈柣锝変憾閺屾盯濡搁妷銉㈠亾瑜版帒桅闁告洦鍨伴崘鈧梺闈涱焾閸庡搫螞濠婂牊鈷戠紒瀣儥閸庢劙鏌熼幖浣虹暫闁糕斁鍋撳銈嗗笂閼冲爼鍩ｉ妶澶嬬厱濠电姴鍟悘瀵糕偓瑙勬礃閸旀瑦淇婇懜闈涚窞濠电姴鎲涢弴銏♀拺闂侇偆鍋涢懟顖涙櫠椤栨粎纾肩紓浣诡焽濞插瓨顨ラ悙宸剶闁诡喗鐟╁鍫曞箣閻欌偓閺嗭繝姊婚崒姘偓椋庣矆娴ｈ鍎熸繝濠傛噹缁躲倝鏌涜椤ㄥ懐绮堥崱娑欑厵闁绘垶锚閻掗箖鏌涘顒夊剰妞ゎ叀娉曢幑鍕瑹椤栨艾澹嬮梻浣告啞钃遍悽顖椻偓鎰佹綎闁惧繗顫夐崗婊堟煕濞戝崬鏋涙繛鎳峰懐纾藉ù锝囨嚀缁茬粯绻涚涵椋庣瘈妤犵偛鍟存慨鈧柕鍫濇噹缁愭稑顪冮妶鍡欏妞ゃ劌鎳忕粋宥嗐偅閸愨晝鍘卞銈庡幗閸ㄥ灚绂嶅┑鍫㈢＜闁煎摜鏁搁崣鈧梺璇″枟椤ㄥ懘锝炲┑瀣拻閻庨潧鎲￠弲鍏肩節绾版ǚ鍋撻崘鑼獓闂佸憡姊归悷鈺呮偘椤旂⒈鍚嬪鑸瞪戦弲鈺呮⒑閼恒儍顏埶囨潏顐犱汗閻庢稒眉缁诲棝鏌ｉ幇鍏哥盎闁逞屽墯閻楃娀骞冭铻栭柛鎰典簽閻撴捇姊洪悷閭﹀殶闁稿鐩畷婵嬪籍閸啿鎷绘繛杈剧到閹诧繝骞嗛崼鐔翠簻闁挎棁顕ф禍鎵偓娈垮枟瑜板啴鈥﹂妸鈺侀唶婵犻潧鐗滃Σ閿嬬節濞堝灝鏋熼柕鍥ㄧ洴瀹曟垿骞樼紒妯煎帾闂佺硶鍓濆ú婊呯玻閺冣偓椤ㄣ儵鎮欏顔煎壎闂佽桨绀侀崯鎾春閳ь剚銇勯幒鎴濃偓鐢稿磻閹炬剚娼╂い鎺戭槹閳诲牓姊洪崫鍕効缂傚秳绀侀锝夘敆閳ь剟鍩㈡惔銊﹀€锋い鎺戝€归悗顕€姊婚崒娆戭槮闁圭⒈鍋婅棟闁告劘灏欓弳锕傛煟閵忋埄鐒鹃柣鎺戠仛閵囧嫰骞掑鍥獥闂佸摜鍠庣换姗€寮诲☉銏″亹鐎规洖娲ら埛灞轿旈悩闈涗沪闁绘濞€閵嗕線寮撮姀鈩冩珖闂侀€炲苯澧撮柟顔惧仱瀹曞綊顢曢悩杈╃泿闂備胶鎳撻幖顐ょ矓閻戣棄绀傜€光偓閸曨剛鍘甸梺鍛婂姇瀵爼宕板鈧弻锛勪沪閻ｅ睗銉︺亜瑜岀欢姘跺蓟濞戙垹绠婚悗闈涙啞閸ｄ即鏌﹀Ο鑽ょ畺闁靛洤瀚板浠嬪Ω瑜忛悡鍌滅磽娴ｆ彃浜炬繝鐢靛Т濞诧箓宕愰悽鍛婄叆婵犻潧妫濋妤呮煛鐎ｎ偆銆掔紒杈ㄥ浮閹亪宕ㄩ婧炴粓姊洪崫鍕伇闁哥姵鐗犲濠氬炊椤掍焦娅囬梺閫炲苯澧撮柟顔缴戝蹇涘Ω瑜忛鏇㈡⒑缁嬭法绠抽柛妯犲嫭鍙忕€广儱顦伴悡娑㈡倶閻愭彃鈷旀繛鎻掔摠椤ㄣ儵鎮欓弶鎴炶癁濡ょ姷鍋為敋闁伙絿鍏樺畷鍫曞煛閸屾碍鐣板┑鐘垫暩婵兘寮崨濠冨弿妞ゆ挶鍨圭壕濠氭煙閸撗呭笡闁绘挶鍎甸弻锝夊棘閹稿孩鍎撻悗娑欑箘缁辨帡鎮欓鈧粈鍐┿亜椤愩埄妲洪柟骞垮灩閳藉濮€閻樿尪鈧灝鈹戦埥鍡楃仴妞ゆ泦鍥ㄥ剭闁硅揪闄勯埛鎴犵磼鐎ｎ偒鍎ラ柛搴㈠姍閺岀喎顫㈠畝濠傛濡炪倖娲忛崕闈涚暦閻旂⒈鏁嶆慨妯挎硾閺佽绻濆▓鍨灍闁挎洍鏅犲畷妤€鈽夊▎鎴犲骄?     */
    private String resolveSelectionStage(SourceCandidate candidate, String stage) {
        String selectionStage = candidate == null ? null : candidate.getSelectionStage();
        if (!StringUtils.hasText(selectionStage)) {
            return stage;
        }
        if (!"PLANNED".equalsIgnoreCase(stage)
                && "PLANNED".equalsIgnoreCase(selectionStage)) {
            return stage;
        }
        return selectionStage;
    }

    /**
     * 闂傚倸鍊搁崐椋庣矆娓氣偓楠炴牠顢曚綅閸ヮ剦鏁冮柨鏇楀亾闁汇倗鍋撶换婵囩節閸屾粌顣虹紓浣插亾濠㈣泛顑嗛崣蹇斾繆閻愰鍤欏ù婊堢畺濮婃椽妫冨☉娆樻缂備浇鍩栧畝鎼佹偘椤旈敮鍋撻敐搴℃灍闁哄懏绻堥弻宥堫檨闁告挻鐩崺鈧い鎺嶆祰婢规ɑ銇勯敂鐐毈鐎殿喖顭烽弫鎰緞婵犲喚妫熼梻浣稿閻撳牓宕板Δ鍜佹晩闁瑰鍋熺弧鈧梺闈涢獜缁插墽娑垫ィ鍐╁殞鐎光偓閸曨剛鍘搁梺閫炲苯澧撮柡浣稿暣瀹曟帒鈽夊顒€绠ラ梻鍌氬€风欢锟犲矗韫囨洜涓嶉柟杈剧畱閸戠娀鏌曢崼婵愭Ч闁绘挻娲熼弻宥夊传閸曨偅娈悗娑欑箞濮婅櫣鈧湱濮甸ˉ澶嬨亜閿旂偓鏆柣娑卞枛椤粓鍩€椤掑嫨鈧礁鈽夊鍡樺兊濡炪倖鍔戦崺鍕涘鍫熲拻闁稿本鑹鹃埀顒勵棑缁牊绗熼埀顒勭嵁閺嶎収鏁冮柨鏃傜帛閺呯偤姊洪崨濠佺繁闁割煈浜畷鎴﹀箻閹颁焦鍍靛銈嗗姂閸ㄥ湱绮婇鈧铏瑰寲閺囩喐婢掗梺?SearchSourceRequest闂?     * 闂傚倸鍊搁崐椋庣矆娓氣偓楠炴牠顢曚綅閸ヮ剦鏁冮柨鏇楀亾闁汇倗鍋撶换婵囩節閸屾粌顤€闂佺顑戠换婵嬪蓟瀹ュ浼犻柛鏇ㄥ墮濞咃綁姊婚崒姘簽闁搞劌鐖煎濠氭晲婢跺﹦顓洪梺鎸庣箓濡厼螞閸曨垱鈷戦柛娑橈功閹冲啴鏌ㄩ弴銊ら偗鐎殿喖顭烽幃銏㈠枈鏉堛劍娅栭梻浣虹《閸撴繈銆冮崱娑橀棷闁兼亽鍎禍婊堟煏韫囨洖校闁搞倕娲﹂幈銊︾節閸曨厼绗＄紓浣诡殘閸犳牕鐣烽幆閭︽Ш缂備降鍔忓畷鐢垫閹惧瓨濯寸紒娑橆儏濞堫厼顪冮妶鍡楃仴婵炲眰鍊曞畵?query闂傚倸鍊搁崐鎼佸磹妞嬪孩顐芥慨姗嗗墻閻掔晫鎲稿鍫罕闂備礁鎼崯顐﹀磹婵犳碍鍎楅柛鈩冦仠閳ь剚甯掗～婵嬫晲閸涱剙顥氶梻鍌欐祰椤曟牠宕伴幒妤€鐒垫い鎺嶇劍閻忛亶鏌＄€ｎ亪鍙勯柡宀€鍠栭獮鍡氼槾闁哄鍟伴幃顔尖攽鐎ｎ偆鍘介柟鑹版彧缁辨洟鎮鹃銏＄厱閹兼番鍔嬮幉鐐殽閻愯宸ユい鎾冲悑瀵板嫮鈧綆浜栭崑鎾诲垂椤旇鏂€闂佺粯蓱瑜板啴鍩€椤掑倹鏆€殿喓鍔嶇粋鎺斺偓锝庡亞閸橀亶鏌ｈ箛鏇炰沪闁稿孩濞婂畷鐢割敆娴ｈ櫣顔曢梺鍏肩ゴ閺呮盯寮稿☉銏＄厸閻忕偟鏅暩濡炪伇鍌滅獢闁哄本绋栫粻娑㈠籍閸屾ǚ鍋撻幒鎾剁闁糕剝鍔曢悘鈺傘亜椤愶絿绠炵€规洩绻濋幃娆撴嚑椤戝灝鏋堥梻鍌氬€烽悞锔锯偓绗涘厾娲冀椤撶偟锛欓梺鍛婄缚閸庢煡宕伴崱娑欑叆闁哄啫鐗婇弳婊堟煕鐎ｎ偅宕岄柣娑卞櫍瀹曞綊顢欓悡搴經闂傚倷鑳堕幊鎾诲疮閸啔瑙勵槹鎼粹€崇亰闂佸壊鍋€閹冲洭宕戦幘缁樻櫜閹煎瓨绻勯幐澶娾攽閳╁啫绲婚柣妤佹崌瀵鎮㈤崗鐓庘偓閿嬨亜閹哄棗浜鹃梺宕囩帛濞叉﹢濡甸崟顒佸劅闁靛繆鏅滈悾鑲╃磽娴ｄ粙鍝洪柟鍛婃倐椤㈡ɑ绺界粙鎸庛仢婵炶揪绲块…鍫熺珶鎼淬劍鐓熼幖娣€ゅ鎰箾閸欏鐒介柛鎺撳笒閻ｆ繈宕熼崹顐ｆ珝濠电娀娼ч崐濠氣€﹂崼婵愬晠婵犻潧娲㈡禍婊堢叓閸ャ劍灏い寰板喚鐔嗛悷娆忓缁€鈧梺瀹狀潐閸ㄥ潡骞冨▎鎾崇煑濠㈣埖蓱閿涗線姊绘担鐑樺殌闁硅绻濋獮鎰板礃閼碱剚娈鹃梺纭呮彧缁犳垹绮绘繝姘厸濠㈣泛顑呴悘鈺冪磼閹邦喖浠遍柡宀嬬稻閹棃顢涘鍛咃綁姊洪崨濠冨鞍闁煎綊绠栭、姘枎瀵版繃妞介、鏃堝川椤忓懎顏归梻鍌欑濠€閬嶁€﹂崼婢濆綊鎮滈挊澶屽弨婵犮垼鍩栭崝鏍偂閻旀悶浜滈柟鎯ь嚟缁犳绱掗幇顓熲拹闁靛洤瀚版慨鈧柨娑樺閸ｄ即姊虹化鏇熸澒闁?Tavily 闂傚倸鍊搁崐椋庣矆娓氣偓楠炴牠顢曚綅閸ヮ剦鏁冮柨鏇楀亾闁汇倗鍋撶换婵囩節閸屾粌顤€闂佺顑戠槐鏇㈠箟濮濆瞼鐤€婵炴垶顭囬悾鍝勵渻閵堝棙纾甸柛瀣尰閵囧嫰濮€閳╁啰顦伴梺杞扮劍閸旀瑥鐣烽崼鏇熸櫜闁糕剝鐟ょ花宄扳攽閻樺灚鏆╁┑顔惧厴瀵偊宕ㄦ繝鍐ㄥ伎闂佽鍎兼慨銈夊吹閸曨垱鐓曟い鎰剁稻缁€鍐煕鐎ｅ墎绡€闁哄瞼鍠栧畷婊嗩槾閻㈩垱鐩弻锝夊箻鐎靛憡鍒涢梺鍝勬湰缁嬫挻绂掗敃鍌氱鐟滄粓寮抽埄鍐瘈闁靛骏绲剧涵楣冩倵濮橆偄宓嗙€殿喖顭烽弫鎾绘偐閹绘帞鐛╃紓鍌氬€搁悧蹇旀叏閻㈢绐楁慨姗嗗劦?provider 闂傚倸鍊搁崐鎼佸磹閻戣姤鍤勯柛顐ｆ礀閸屻劎鎲搁弮鍫濈畺闁秆勵殔閻愬﹪鏌曟繝蹇曠暠鐎殿喖娼″娲濞戞艾顣哄┑鐐跺皺婵炩偓鐎规洘鍨块獮妯肩磼濡粯鐝抽梻浣告啞濞诧箓宕㈡ィ鍐╁仒闁靛繈鍊栭崐鐢告偡濞嗗繐顏紒鈧崘顏佸亾閸忓浜剧紓浣割儐椤戞瑩宕甸弴銏＄厱闁规崘灏欓崝宥団偓瑙勬礀椤︾敻寮婚弴鐔虹瘈闊洦绋掗宥咁渻閵堝棙鐓ラ柟铏～蹇曠磼濡顎撻梺鍏间航閸庮垶鍩€椤掆偓閸熸挳寮婚妶澶婄闁肩⒈鍓欓悡鐔兼煢濡厧鏋涢柡宀€鍠栭弻鍥晝閳ь剟寮搁悢鎼炰簻闁归偊鍨拌闂侀潧娲ょ€氫即鐛鈧畷锟犳倷閸忓憡鍋呯紓鍌氬€风拋鏌ュ磻閹剧粯鐓曠€光偓閳ь剟宕戦悙鐑樺亗闊洦鎼╅悢鍡涙煠閸濄儲鏆╅柕鍡樺笧閳ь剙鍘滈崑鎾寸箾閹存瑥鐏柣?     */
    private SearchSourceRequest buildSearchSourceRequest(CollectorNodeConfig config,
                                                         List<SourceCandidate> allCandidates,
                                                         ResolvedFieldEvidenceQueryPlan fieldEvidenceQueryPlan,
                                                         Long fieldEvidenceExecutionDeadlineEpochMillis) {
        return SearchSourceRequest.builder()
                .competitorName(config.getCompetitorName())
                .requestedScopes(List.of(config.getSourceType()))
                .searchQueries(resolveSearchQueries(config, null))
                .fieldEvidenceQueries(fieldEvidenceQueryPlan == null ? List.of() : fieldEvidenceQueryPlan.getExecutable())
                .fieldEvidenceQueryPlannedCount(fieldEvidenceQueryPlan == null ? 0 : fieldEvidenceQueryPlan.getPlanned().size())
                .fieldEvidenceQueryExecutableCount(fieldEvidenceQueryPlan == null ? 0 : fieldEvidenceQueryPlan.getExecutable().size())
                .fieldEvidenceQuerySkippedCount(fieldEvidenceQueryPlan == null ? 0 : fieldEvidenceQueryPlan.getSkipped().size())
                .fieldEvidenceExecutionDeadlineEpochMillis(fieldEvidenceExecutionDeadlineEpochMillis)
                .preferredDomains(defaultList(config.getPreferredDomains()))
                .includeDomains(defaultList(config.getIncludeDomains()))
                .blockedDomains(defaultList(config.getBlockedDomains()))
                .seedCandidates(allCandidates == null ? List.of() : allCandidates)
                .preferredProviderKey(config.getPreferredSearchProvider())
                .preferredQueryMode(config.getTavilyQueryMode())
                .requestPhase(SearchRequestPhase.SUPPLEMENT)
                .build();
    }

    private List<String> resolveSearchQueries(CollectorNodeConfig config, SearchExecutionPlan executionPlan) {
        if (config.getSearchQueries() != null && !config.getSearchQueries().isEmpty()) {
            return config.getSearchQueries();
        }
        if (executionPlan != null && executionPlan.getSearchQueries() != null) {
            return executionPlan.getSearchQueries();
        }
        return List.of();
    }

    private List<String> defaultList(List<String> values) {
        return values == null ? List.of() : values;
    }

    /**
     * 闂傚倸鍊搁崐宄懊归崶顒夋晪鐟滃繘鍩€椤掍胶鈻撻柡鍛箘閸掓帒鈻庨幘宕囶唺濠碉紕鍋涢惃鐑藉磻閹捐绀冩い鏃傚帶閼板灝鈹戦悙鏉戠伇濡炲瓨鎮傚鏌ュ煛閸涱喖鈧敻鏌涜箛鎿冩Ц濞存粓绠栧娲焻閻愯尪瀚板褎鎸抽弻鐔碱敍濡も偓娴滅偓淇婇悙顏勨偓鏇犳崲閹邦優褰掑磼濮ｈ偐鍠愮粭鐔煎焵椤掆偓椤繘鎮滃Ο渚殼濠电偛妫欓崹褰掑汲閻樺樊娓婚柕鍫濈箰椤╊剟鏌℃担鍓茬吋闁诡喕鍗抽、姘跺焵椤掑嫮宓侀悗锝庡枟閺呮粎绱撴担鑲℃垵鈻嶉幘瀵哥瘈闁汇垽娼ф禒婊勪繆椤愶絿鎳囩€规洘娲熼獮搴ㄦ寠婢跺瞼鏆┑鐐存尰閸╁啴宕戦幘瀛樺弿濠电姴鍟妵婵堚偓瑙勬磸閸斿秶鎹㈠┑瀣＜婵犲﹤鎳忛崵鍐⒒娴ｅ湱婀介柛鈺佸瀹曞綊骞庨挊澶岋紱闂侀潧鐗嗛幏瀣吹?workflow 闂傚倸鍊搁崐宄懊归崶褏鏆﹂柣銏㈩焾缁愭鏌熼幍顔碱暭闁稿绻濋弻鏇熺珶椤栨浜鹃梺绋款儐閹告悂锝炲┑瀣亗閹肩补妾ч幏顐︽煟鎼淬値娼愭繛鍙夌矒楠炲﹪骞樼拠鑼幋闂佺鎻粻鎴︽煁閸ャ劎绡€濠电姴鍊搁鈺佲攽椤栨瑥宓嗘慨濠勭帛閹峰懏绗熼婊冨Ъ闂備礁鎼悧婊堝礈閻旂厧绠犳繝闈涙储閸嬪懘鏌涢幇鈺佸闁哄睙鍥ㄢ拺鐟滅増甯楅敍鐔兼煟閹虹偟鐣甸柟?config闂傚倸鍊搁崐鎼佸磹閻戣姤鍊块柨鏃堟暜閸嬫挾绮☉妯诲櫧闁活厽鐟╅弻鐔告綇閸撗呮殸闁诲孩鑹鹃ˇ浼村Φ閸曨垰绠抽柟瀛樼箥娴犺偐绱撴担鍝勑ｉ柣妤佺矒閸┾偓妞ゆ帒鍠氬鎰箾閸欏澧悡銈夋煥閺囩偛浜扮紓宥嗙墵閺岋繝宕堕妷銉т痪闂佺粯鎸哥换姗€鎮￠锕€鐐婇柕濠忓椤︺儵鏌涢悢渚劸闁宠鍨块幃鈺咁敃椤厼顥氶梻鍌欑濠€閬嶆惞鎼淬劌绐楁俊銈呮噺閸嬪倿鏌￠崶鈺佹灁缂佺娀绠栭弻娑㈠焺閸愶絾锛堥梺缁樻尭缁″啰鎲撮崟鍨ч柟鑹版彧缁插潡鏁嶅鍫熲拺闂侇偆鍋涢懟顖涙櫠娴煎瓨鐓冪憸婊堝礈閵娧呯闁糕剝绋戠粈鍫熸叏濡顣抽柛瀣尭椤繈顢楁径灞芥倯闂備礁鎼悮顐﹀礉瀹€鍕厴闁硅揪绠戦獮銏′繆椤栨粌鍔嬫い蹇曞█濮婄粯鎷呴崨濠冨創闂佺锕ラ崹鍨暦閸洖惟鐟滃秹鐛幇鐗堢厽閹艰揪绱曢悾顓㈡煕鎼粹€宠埞閻撱倝鏌曢崼婵愭Ц闁稿被鍔戦弻鐔虹磼閵忕姵鐏嶉梺缁樻尭閸熶即骞夌粙娆剧叆闁割偅绻勯ˇ顓炩攽閻愬弶顥為柛銊ㄦ硾閻ｇ兘寮婚妷锕€鈧敻鏌ㄥ┑鍡涱€楀褜鍨遍妵鍕敃閿濆棛顦伴梺鍝勬湰閻╊垶鐛鈧幊鐘垫崉閸濆嫬鑵愮紓?provider闂?     * 闂傚倸鍊搁崐椋庣矆娓氣偓楠炴牠顢曚綅閸ヮ剦鏁冮柨鏇楀亾闁汇倗鍋撶换婵囩節閸屾粌顤€闂佺顑戠换婵嬪蓟閵娾晛鍗抽柣鎰ゴ閸嬫捁銇愰幒鎴狅紱?Tavily/HTTP provider 闂傚倸鍊搁崐鎼佸磹妞嬪海鐭嗗ù锝堟缁€濠傗攽閻樻彃浜為柣鎺旀櫕閹叉瓕绠涢弴鐐茬亰闂佸搫鍟悧濠囧磹婵犳碍鐓㈡俊顖欒濡茬儤銇勮熁閸愶絾鏂€濡炪倖鏌ㄩ～鏇熺濠婂牊鐓曢悗锝庡亝鐏忕敻鏌嶈閸撴繈锝炴径濞掑搫顭ㄩ崼婵堫槯濠碘槅鍨甸濠勬崲閸℃ɑ鍙忔繝闈涙閻掔偓淇婃穱鍗炲婵﹨娅ｇ划娆撳垂椤曞懎濡烽梻浣规偠閸斿酣寮繝姘槬闁靛绠戠欢鐐烘煙闁箑澧版い鏃€甯掗—鍐Χ閸℃瑥顫ч梺娲诲弾閸犳绮╅悢鐓庡嵆闁靛繆妾ч幏?supplement 闂傚倸鍊搁崐宄懊归崶顒夋晪鐟滃繘骞戦姀銈呯疀妞ゆ棁妫勬惔濠傗攽鎺抽崐鎾绘⒔瀹ュ棛顩烽柛顐犲劜閻撴洘绻涢幋鐑囧叕闁衡偓缂佹绠鹃柛娑卞灠閳诲牓鏌″畝瀣М闁轰焦鍔欏畷銊╊敊閼恒儱顏伴梻?field-first 濠?query闂?     */
    private List<FieldEvidenceQuery> resolveFieldEvidenceQueries(CollectorNodeConfig config) {
        if (config == null || config.getDimensionEvidencePlan() == null) {
            return List.of();
        }
        DimensionEvidencePlan plan = config.getDimensionEvidencePlan();
        if (plan.getFieldCoverages() == null || plan.getFieldCoverages().isEmpty()) {
            return List.of();
        }
        /*
         * 闂傚倸鍊搁崐鎼佸磹妞嬪海鐭嗗〒姘ｅ亾妤犵偛顦甸弫鎾绘偐閸愯弓鐢绘俊鐐€栭悧妤冪矙閹炬眹鈧懘宕ｆ径宀€鐦堥梻鍌氱墛缁嬫帡鎯屽Δ鍐＜閻犲洤寮堕ˉ銏ゆ煛鐏炲墽鈽夐柍钘夘槸椤粓宕煎┑鍡╂浆濠碉紕鍋戦崐鎰板疾閻樺嚢澶愬箻鐠囪尙鍔﹀銈嗗坊閸嬫挾绱掗悩鑼х€规洘娲熷畷锟犳倷瀹ュ棛鈽夐柍璇叉唉缁犳盯鏁愰崨顕呭悪闂傚倷娴囬～澶愬磿閹剁瓔鏁嬫い鎾跺Т婵剟鏌嶈閸撴氨鎹㈠┑瀣仺闂傚牊绋愬▽顏堟⒑閸涘﹥鈷愰柛銊ョ仢閻ｇ兘骞囬弶鍨祮闂侀潧绻掓慨鐑芥偘閵夆晜鈷戦柛婵嗗閸屻劑鏌涢妸锔姐仢闁诡噯绻濋幃銏ゅ礂閼测晛甯惧┑鐘垫暩閸嬫盯鎮樺┑瀣婵﹩鍘规禍婊堢叓閸パ嶆敾婵炲懎锕弻鈥崇暆鐎ｎ剛袦闂佺硶鏅涢敃銈夊煝鎼淬倗鐤€闁规儳鐡ㄩ悿渚€姊?query 濠电姷鏁告慨鐑藉极閹间礁纾婚柣鎰惈閸ㄥ倿鏌涢锝嗙缂佺姳鍗抽弻鐔兼⒒鐎垫瓕绐楅梺杞扮鐎氫即寮诲☉妯锋婵犲﹤鍟伴崝閿嬬箾鐎涙鐭婄紓宥咃躬瀵鈽夊顐ｅ媰闂佸憡鎸嗛崨顖氬笓缂?provider闂?         * 闂傚倸鍊搁崐宄懊归崶褏鏆﹂柛顭戝亝閸欏繒鈧娲栧ú锔藉垔婵傚憡鐓涢悘鐐额嚙閸旀岸鏌ｉ幒鎴犱粵闁靛洤瀚伴獮鎺楀幢濡炴儳顥氶梻鍌欑閹芥粓宕版惔鈽嗙劷闁跨喓濮寸粻鏍ㄤ繆椤栨繂鍚圭紒鈾€鍋撻梻浣规偠閸庮噣寮插☉婧夸汗闁告洦鍨遍埛鎴︽煙閼测晛浠滈柛鏂诲劜缁绘稒寰勭€ｎ兘濮囩紓浣稿€哥粔褰掑箖濞嗘挻鍊绘俊顖滃帶楠炲牊绻濋悽闈涗粶婵☆偅顨堥幑銏狀潨閳ь剙鐣烽敐澶婄妞ゆ牗绋撻崢鎼佹煟韫囨洖浠ч柡鍜佸亰閹﹢宕￠悘璇茬秺閹亪宕ㄩ婊勬闂備礁婀遍…鍫ユ晝閵夈儺鍤楅柛鏇ㄥ墯閸庣喐銇勯弬鍨倯闁哄棭鍙冨濠氬磼濮橆兘鍋撳畡鎳婂綊宕堕妸褏褰鹃梺鍓插亝缁洪箖寮稿澶嬬厸闁搞儯鍎遍悘鈺呮煕閵娿儱鈧湱鎹㈠┑瀣棃婵炴垶鑹鹃·鈧柣搴ゎ潐濞叉牕顕ｉ崼鏇炵疄闁靛鍎欓悢鐓庡瀭妞ゆ梹鍎崇敮鍧楁⒒娴ｄ警鐒炬い鎴濆€垮鎻掝煥閸涱喖搴婂┑鐐村灟閸ㄥ綊鐛姀鈥茬箚闁靛牆鎳庨弳鐔搞亜閺傛寧鍠樻慨濠呮缁棃宕卞Δ鈧弸锕傛椤掑澧撮柡?plannedQueries 闂傚倸鍊搁崐鎼佸磹閻戣姤鍊块柨鏇楀亾妞ゎ亜鍟村畷褰掝敋閸涱垰濮洪梻浣侯潒閸曞灚鐣剁紓浣插亾濠㈣埖鍔栭崐鐢告煥濠靛棝顎楀褎褰冮埞鎴︻敊閹稿海褰ч梺闈涙搐鐎氫即鐛幒妤€绠ｆ繝闈涘暙娴滈箖鏌ｉ姀銏╃劸缁炬儳顭烽弻鐔煎箚瑜忔禍顏堟煕鐎ｎ偅灏柍钘夘樀瀵€燁槷闁稿鎹囬獮瀣晝閳ь剛澹曡ぐ鎺撶厪闁割偅绻嶅Σ鎼佹偡濞嗘瑧鐣甸柡灞界Х椤т線鏌涢幘璺烘瀻妞ゎ偄绻愮叅妞ゅ繐瀚鍥煙閼圭増褰х紒鎻掓惈鍗遍柛顐ゅ枔缁♀偓闂傚倸鐗婄粙鎴﹀焵椤掑倹鍤€妞ゎ偄绻掗幏鐘差啅椤斿吋顔傚┑鐘垫暩婵潙煤閵堝洨鐭嗛柛顐犲灪閸犳劕顭块懜闈涘缁炬儳顭烽弻鐔兼倷椤掍胶浼囧┑鈩冨絻閻楀﹦鎹㈠☉銏犲耿婵☆垱娲橀崹鍧楀箖閹呮殝闂侇叏闄勭€靛矂姊洪棃娑氬婵☆偅鐟╅幃锟犲Ψ閳哄倻鍘惧┑鐐跺蔼椤曆囨倶閿斿浜滈柕蹇ョ磿閹冲棛绱掗悩宕団槈闁宠棄顦埢搴ょ疀閺冩垵鎮嬮梻鍌氬€风粈渚€宕ョ€ｎ喖绠栭柛灞惧嚬閻掔晫鎲稿澶婄叀濠㈣泛瀵掑Ο鍕攽椤旂》鏀绘俊鐐舵閻ｇ兘顢曢敃鈧粈瀣亜閹炬剚妲堕柛銊ㄦ閹广垹鈽夊顐ｅ媰闂佺鏈崺鍐磻閹炬枼鏀介悗锝庡墮缁侊箓姊洪崨濠傚闁告搩鍣ｅ顕€鍩€椤掑嫬绠柛娑卞灡閸嬫﹢鏌嶉埡浣告殶濞存粍鐟╁铏规嫚閺屻儺鈧绱掗悩鑼х€规洘娲熼弻鍡楊吋閸℃ぞ缃曢梻浣虹《閸撴繄绮欓幋锕€纾奸柕濞炬櫆閻撴瑧绱撴担闈涚仼闁哄鍠栭弻锝夊箻鐎涙顦伴梺鍝勭灱閸犳牠寮婚崶顒佹櫇闁逞屽墯閺呰埖瀵奸弶鎴濆敤闂佺偨鍎查崜姘€掓繝姘厪闁割偅绻冮ˉ婊冣攽椤斿吋鍠橀柡灞炬礋瀹曞ジ鎮㈢粙搴撳亾閹扮増鐓熸繛鎴濆船閺嬫稒銇勯幘鐐藉仮鐎规洖銈搁、鏇㈡晲閸屾稓鈧鲸绻濈喊澶岀？闁稿鍨垮畷鎰板箣閿曗偓閸ㄥ倹绻涘顔荤盎闁搞劌鍊块弻娑滎槼妞ゃ劌鎳樺鍛婃媴鐞涒€充壕妤犵偛鐏濋崝姘箾鐠囇呭埌闁宠绮欓、鏃堝醇閻斿搫骞橀梻浣筋嚃閸樼晫鏁幒妤€绀夋慨妯垮煐閻撴洟鏌熼幆褍鑸瑰┑顔煎€归幈銊︾節閸愨斂浠㈤悗瑙勬磸閸斿秶鎹㈠┑瀣＜婵犲﹤鎳忛崵鍐⒒閸屾瑦绁扮€规洜鏁诲畷浼村幢濞戞锛欏┑掳鍊愰崑鎾诲础闁秵鐓欓梺顓ㄧ畱楠炴牗銇勯弴顫喚闁哄备鍓濆鍕偓锝庝簽娴犵厧顪冮妶鍡樼叆闁瑰啿绻掗幑銏犫攽鐎ｎ偄浠洪梻鍌氱墛缁嬫劕危閹扮増鈷戦弶鐐村鐠愪即鏌涢敐蹇曞埌闁伙絿鍏橀獮瀣晝閳ь剛绮绘繝姘厵濡娴囬崗宀勬煕濞嗗繑顥㈡慨濠冩そ瀹曘劍绻濋崒姘兼綆闂備礁鎲￠弻銊╂嚐椤栫偛鐓濋柟鎹愵嚙鍞梺鍐叉惈閸婄敻骞忔繝姘拺缂佸瀵у﹢浼存煠閸︻厼浜剧紒鍌氱Ч閹瑩鎮滃Ο鐓庡箞婵犳鍠楅妵娑㈠磻閹惧墎纾奸柣妯虹－婢х敻鏌熼璇插祮妞ゃ垺宀搁崺鈧い鎺嗗亾闁伙絿鍏樺畷锟犳倷閳哄倻鈧鏌ｈ箛鏇炰沪闁搞劍绻冪粩鐔煎即閻愨晜鏂€闂佺粯鍔栧娆撴倶閿曞倹鐓熼柣鏇氱閻忕娀鏌嶇紒妯诲磳鐎殿喗鎸抽幃銏㈢礄閻樼數娉块梻鍌欑閹碱偊宕愰挊澶嗘灃闁哄洢鍨洪崐鍧楁煙闂傚鍔嶉柣鎾存礋閺屾洘绻濊箛鏇犳殸闂佸憡鏌ｉ崐婵嬪蓟瀹ュ洦鍠嗛柛鏇ㄥ亞娴犲摜绱撴担铏瑰笡缂佽鍟撮獮鍡涘籍閸繄鍔撮梺鍛婂姈閸庢娊寮妶澶嬧拻濞达綀妫勯崥褰掓煕閻樺啿濮夐柛鎺撳笚閹棃濮€閳╁啰褰挎繝寰锋澘鈧洟骞婃惔锝囦笉闁哄绨遍弨浠嬫煟濡櫣鏋冨瑙勶耿閺屾盯濡搁妷銉㈠亾瑜版帒绠為柕濞垮労濞笺劑鏌涢埄鍐炬當闁抽攱甯″娲箹閻愭祴鍋撻弽顓溾偓鍐╃節閸パ嗘憰濠电偞鍨惰彜婵℃彃鐗婃穱濠囶敍濮橆厽鍎撶紓浣瑰絻閸氬鎹㈠┑瀣潊闁挎繂鎳愰崢顐︽⒑閸涘﹥鈷愰柣妤冨█楠炲啴鏁撻悩鑼紲闂佺粯鍔曢顓㈠储闁秵鈷戦梻鍫熶緱濡狙呯磼闊厾鐭欑€规洘绻傝灃闁告侗鍠氶崢?         */
        return plan.getFieldCoverages().stream()
                .filter(Objects::nonNull)
                .filter(field -> !isFieldCoverageSatisfied(field))
                .flatMap(field -> field.getPlannedQueries() == null
                        ? java.util.stream.Stream.empty()
                        : field.getPlannedQueries().stream())
                .filter(Objects::nonNull)
                .toList();
    }

    private boolean isFieldCoverageSatisfied(FieldEvidenceCoverage field) {
        if (field == null) {
            return false;
        }
        /*
         * 闂傚倸鍊搁崐宄懊归崶顒夋晪鐟滃繘鍩€椤掍胶鈻撻柡鍛箘閸掓帒鈻庨幘宕囶唺濠碉紕鍋涢惃鐑藉磻閹捐绀冩い鏃傚帶閼板灝鈹戦悙鏉戠伇濡炲瓨鎮傚鏌ュ煛閸涱喖鈧敻鏌涜箛鎿冩Ц濞存粓绠栧娲川婵犲啫顦╅梺绋款儏鐎氼剟顢氶敐鍛殝闂侇叏闄勭€靛矂姊洪棃娑氬婵☆偅绋掗弲鍫曨敆閸屾粍锛忛梺鍝勵槼濞夋洘鏅ラ柣搴ゎ潐濞叉ê煤濡吋宕叉繝闈涱儏閻愬﹪鏌曟繛鍨仾閻庢矮绮欏缁樻媴娓氼垳鍔搁柣鐐村嚬閸嬪﹤鐣烽幇鏉垮嵆闁靛繈鍨圭粊锕€鈹戦埥鍡楃仭婵＄偛鐏濋埢鎾寸鐎ｎ偆鍘介梺褰掑亰閸撴瑧鐥閵囧嫰濡疯娴犙呯磼鏉堛劌绗ч柍褜鍓ㄧ紞鍡樼鐠轰綍锝夊川婵炲じ绨婚梺鐟板⒔閸嬨劑宕戦姀鈶╁亾鐟欏嫭绀堥柛妯犲洤鐓橀柟杈剧畱楠炪垺淇婇妶鍛灓闁告艾鎳樺濠氬磼濮橆兘鍋撴搴ｇ焼濞撴埃鍋撴鐐差樀閺佹捇鎮╅崘韫敾婵＄偑鍊栭悧妤冪矙閹炬眹鈧懘寮婚妷锔惧幗闂佸綊鍋婇崹濂稿吹鐎ｎ喗鐓?completedPaths闂?         * completedPaths 闂傚倸鍊搁崐宄懊归崶褏鏆﹂柛顭戝亝閸欏繘鏌℃径瀣婵炲樊浜滃洿婵犮垼娉涢鍛闁秵鈷戦梻鍫熶緱閻掗箖鏌涙惔銏㈡噰闁轰焦鍔栧鍕節閸曢潧鎮堥梻鍌欑劍鐎笛兠哄澶婄；闁瑰墽绮悡鐔肩叓閸ャ劎顣查柣婵愪簽缁辨帞绱掑Ο鑲╃暫缂備胶绮换鍫濈暦閹烘垟妲堥弶鍫厛濡冣攽閻樺灚鏆╅柛瀣洴閹囧箻閸撲椒绗夐梺鍝勭▉閸樿偐绮婚弽銊ょ箚妞ゆ牗绻冪涵鍫曟煟閵堝倸浜鹃梻鍌欑閹碱偊宕愰挊澶嗘灃闁哄洨鍠撻々椋庣磼鐎ｎ亞姘ㄩ柡鈧禒瀣厽婵妫楁禍婊兠瑰鍛壕缂佺粯鐩獮姗€寮堕幋鐘插Р闂備胶顭堥鍡涘箰妤ｅ啫鐒垫い鎺嶇贰閸熷繘鏌涢悩宕囧⒌闁炽儻绠撻幃婊堟偩鐏炵晫銈﹂梻浣规偠閸庢椽宕滈敃鍌氭瀬闁搞儺鍓氶悡鐔兼煛閸愩劌鈧崵鏁☉銏″€垫慨妯煎帶婢у瓨鎱ㄦ繝鍐┿仢闁圭绻濇俊鍫曞川閸滃啰绡€闁诡喖缍婇崺鈧い鎺戝閺呮繈鏌涚仦鎹愬闁诲寒鍙冨铏规喆閸曢潧鏅遍梺鍝ュУ椤ㄥ﹪銆侀弴鐔侯浄閻庯綆鍋嗛崢浠嬫⒑瑜版帒浜伴柛鎾村哺楠炲棝宕奸埗鈺佷壕闁荤喓澧楅崯鐐电磼鐠囪尙澧︾€殿喖顭烽幃銏㈡偘閳ュ厖澹曞┑鐐村灦椤忣亪顢旈崱娆戠暥闂佸啿鎼幊蹇涙偂閺囩喐鍙忔俊銈傚亾婵☆偅顨嗛弲鍓佲偓鐢电《閸嬫挸鈻撻崹顔界亪闂佺粯鐗曢妶绋跨暦濞差亜鐒洪柛鎰ㄦ櫅椤庢捇姊洪棃鈺佺槣闁告瑥楠稿嵄婵炲樊浜濋埛鎺戙€掑锝呬壕闂侀€炲苯澧伴柛瀣洴閹崇喖顢涘☉娆愮彿濡炪倖鐗楃划搴ｅ閼测晝纾藉ù锝咁潠椤忓懏鍙忓璺侯儎缁诲棝鏌涘▎蹇ｆЦ濠殿喖娲弻宥囨喆閸曨偆浼岄悗瑙勬礀閻栧吋淇婇幖浣肝ㄦい鏃傛嚀娴?sourceUrls 闂傚倸鍊搁崐鎼佸磹妞嬪海鐭嗗ù锝堟缁€濠傗攽閻樻彃浜為柣鎺旀櫕閹叉瓕绠涢弴鐐茬亰闂佸搫鍟悧濠囧磹婵犳碍鐓㈡俊顖欒濡茬儤銇勮熁閸愶絾鏂€闂佸疇妫勫Λ妤呮倶閻樼粯鐓曟慨姗嗗墻閸庢垿鏌嶈閸撴氨鍠婂鍛殕闁归棿绀佺粻鏍ㄤ繆閵堝倸浜鹃梺瀹犳椤︻垶鍩㈠澶嬫優妞ゆ劑鍨绘导宀勬⒑閹稿海绠橀柛瀣ㄥ€曢锝嗙鐎ｅ灚鏅ｉ梺缁樻煥閹碱偆鏁Δ鍛拻濞撴埃鍋撴繛浣冲毝銊╁焵椤掆偓閳规垿鍨鹃搹顐㈡灎闂佽鍨伴惉鍏肩閿曞倸绀堢憸蹇涙儊閸儲鈷戦梺顐ゅ仜閼活垱鏅堕幘顔界厸濞达綀顫夊畷宀勬煙椤旇娅婄€规洘锕㈤獮鎾诲箳閹搭厽顥夐梻鍌氬€搁崐椋庣矆娓氣偓楠炲鍨剧搾浣规そ閺佸啴宕掑槌栨Ф婵犵數鍋涘Λ娆撳箰婵犳艾纾婚柨鐔哄Х閸欐捇鏌涢妷锝呭闁愁垱娲熼弻锝夊箻鐎涙顦伴梺鍝勭灱閸犳牠骞冨鍐炬建闁糕剝锕╅崥鍌炴⒒娴ｅ憡鎯堟俊顐ｇ懅閺侇噣鎮欓崹顐綗闂佸湱鍎ら妵娑㈠焵椤掑﹦鐣垫鐐村浮瀵噣宕掗敂鎯ф優闂傚倸鍊烽懗鍫曞箠閹捐围闁告縿鍎虫稉宥夋煛瀹擃喖鎳忓▓鎯р攽閳藉棗鐏熼悹鈧敃鍌氱柈闁告侗鍠撴禍婊堟煛閸愩劌鈧骞楅崒鐐寸厓鐟滄粓宕滃▎鎴犵煋闁汇垹鐏氬畷鍙夌節闂堟侗鍎忔い顐㈡嚇閺屻劌鈹戦崱妯烘闂佸憡锕╅崑鍕煘閹达富鏁婇悷娆愬笚缁挸鐣峰┑鍡欐殕闁告洦鍋嗛崢娲⒑鐟欏嫬绀冩い鏇嗗懐涓嶉柨婵嗩槹閻撶喖鏌熼柇锕€娅橀柡鍡╁灦閺岋紕鈧綆鍋嗘晶鐢告煙椤旂瓔娈滈柣娑卞櫍瀹曞綊顢欓悡搴經闂傚倷鑳堕幊鎾诲疮鐠恒劍宕叉俊顖濇閺嗭箓鏌熼幍顔碱暭闁绘帟鍋愰埀顒€绠嶉崕鍗炍涘Δ浣侯浄妞ゆ牜鍋為埛鎴︽煙閼测晛浠滈柛鏃堟涧閳规垿鍨鹃搹顐㈡灎閻庤娲樼换鍌烆敇婵傜鐐婇柨婵嗘噸婢规洟鏌ｉ悢鍝ユ噧閻庢凹鍘炬禍鎼侇敃閿旂晫鍘介梺纭呮閸嬬喖鎮鹃棃娑掓斀闁挎稑瀚弳顒傗偓瑙勬礈閸犳牠銆佸☉妯锋婵☆垰婀辩槐锕傛⒒閸屾瑧顦﹂柟璇х磿缁瑩骞嬮敂鑺ユ珖闂侀潧顦弲娑滅箽闂備礁鎲￠崝锕傚窗濡ゅ懎鐓曢柟杈鹃檮閻撶喖鏌熼柇锕€鐏℃い銉ヮ樀閺屾盯寮埀顒傚垝鎼达絾顫曢柟鐑橆殔娴肩娀鏌涢弴銊ュ濞寸姵鎸冲铏瑰寲閺囩喐婢掗梺?         * 濠电姷鏁告慨鐑藉极閹间礁纾婚柣鎰惈閸ㄥ倿鏌涢锝嗙缂佺姳鍗抽弻娑㈩敃閿濆棛顦ョ紒鐐劤缂嶅﹪寮婚悢鐓庣鐟滃繒鏁☉銏＄厽闁规儳鍟块埀顒€婀遍幑銏犫攽閸モ晝鐦堥梺绋挎湰缁矂銆傞搹鍦＝濞达絽鎼悵锟犳煕閵夛絽濡块柛妯绘崌濮婃椽妫冨☉杈ㄐら梺绋挎唉娴滎剟鎯€椤忓浂妲奸梺闈涙搐鐎氫即鐛崶顒夋晜闁糕剝鐟ч崢顖涚節濞堝灝鏋涢柨鏇樺劚椤啯绂掔€ｃ劉鍋撴担绯曟瀻闁规儳鍟块悗顓烆渻閵堝棙鐏濋柛鏇ㄥ墮閺嗘姊洪柅鐐茶嫰婢ь垱銇勯弮鈧悧鐘茬暦椤栫儐鏁冮柨鏇楀亾鐎瑰憡绻冮妵鍕籍閸ヮ煈妫勯梺鍛婃⒐瀹€绋款潖缂佹ɑ濯撮柛娑橈工閺嗗牓姊洪崨濠冣拹婵炲弶鐗犻、姘舵晲婢跺﹪鍞堕梺鍝勬川閸嬬喖鏁嶅▎鎾粹拺婵懓娲ら悘鈺呮煙鐠囇呯瘈闁诡噯绻濇俊鐑芥晜閸撗屽晭闂備胶鎳撻顓㈠磹閺嶎厼绠ｆ繝鍨崄閳ь剙娼￠弻鈩冨緞鐎ｉ潧鍔岄梺缁樻煥濡盯骞夊宀€鐤€闁哄啫鍊婚鏇㈡⒑闁偛鑻晶浼存煃瑜滈崜婵嬶綖婢跺⊕鍝勵潨閳ь剟濡撮崘顔煎耿婵炴垶顭囬鍡涙⒑閸涘﹣绶遍柛銊ㄦ珪閵囨瑩骞庨懞銉モ偓鐢告煥濠靛棛鍑圭紒銊ュ悑缁?coordinator 闂傚倸鍊搁崐鎼佸磹閻戣姤鍤勯柛顐ｆ磵閳ь剨绠撳畷濂稿Ψ椤旇姤娅堥梻浣虹《閸撴繈鎮烽妷鈺佸瀭婵犻潧鐗冮崑鎾荤嵁閸喖濮庡┑鐐额嚋缁犳挸顕ｉ崘宸叆闁割偆鍠撻崢鎾绘偡濠婂嫮鐭掔€规洘绮撻幃銏ゆ偂鎼淬倖鎲版繝鐢靛仦閸ㄥ爼鏁冮埡渚囩劷闁冲搫鍊舵禍婊堟煙閸濆嫮肖闁告柨绉堕埀顒冾潐濞叉ɑ绻涙繝鍌ゆ綎?planned queries闂?         */
        int minimumAttemptedPaths = field.getMinimumAttemptedPaths() == null
                ? 1
                : Math.max(0, field.getMinimumAttemptedPaths());
        int minimumDistinctEvidenceCount = field.getMinDistinctEvidenceCount() == null
                ? 0
                : Math.max(0, field.getMinDistinctEvidenceCount());
        int completedPathCount = distinctNonBlankCount(field.getCompletedPaths());
        int distinctSourceUrlCount = distinctNonBlankCount(field.getSourceUrls());
        return completedPathCount >= minimumAttemptedPaths
                && distinctSourceUrlCount >= minimumDistinctEvidenceCount;
    }

    private int distinctNonBlankCount(List<String> values) {
        if (values == null || values.isEmpty()) {
            return 0;
        }
        return (int) values.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .count();
    }

    /**
     * 闂傚倸鍊搁崐鎼佸磹瀹勬噴褰掑炊瑜滃ù鏍煏婵炲灝鍔存繛鎾愁煼閺岀喖鎮滃鍡樼暥缂佺虎鍘搁崑鎾绘⒒娴ｇ瓔娼愰柛搴ｅ帶铻為柛鏇ㄥ灡閳锋帗銇勯弽顐沪闁绘搫缍侀悡顐﹀炊閵娧€鏋旈梺绋款儐閹告悂鍩ユ径濞炬瀻闊洦鎼╅埀顒€绻樺濠氬磼濞嗘帒鍘″銈庡幖閻楁挸顕ｉ悽鍓叉晢闁告洦鍓欏▓鐐烘⒑鐠団€崇€婚柍褜鍓欏嵄闁割偁鍨洪崰鎰版煛閸愩劎澧曠紒鐘崇墱閹叉悂鎮ч崼婵堢懆缂備胶濮电粙鎺楀Φ閸曨垰妫橀柛顭戝枟閸婎垶姊虹拠鑼婵☆偅绻傞～蹇涘传閸斿€熸閹风娀骞撻幒鏃戝晥濠碉紕鍋戦崐鎴﹀礉瀹€鍕櫇妞ゅ繐鐗婇崑妯汇亜閺囨浜惧Δ鐘靛仜濞差參銆佸鈧幃娆撴偨閻㈤潧绁﹂梻鍌欐祰椤曆呮崲閹烘纾婚柣妯哄棘濞戙垹绀嬫い鎾寸☉娴?priority 缂傚倸鍊搁崐鎼佸磹閹间礁纾瑰瀣捣閻棗銆掑锝呬壕濡炪們鍨洪悧鐘茬暦閵娾晛绾ч柟瀵稿У閻掗箖姊绘担渚綊闁告洖鐏氶悾鐑芥⒑缁嬫鍎岄柡鍛閻忓啴姊洪幐搴ｇ畵闁瑰啿閰ｅ鍐测枎閹寸姷锛滈梺缁樏崯鍧楀煝閺囥垺鐓涚€光偓閳ь剟宕伴弽顓炵畺婵犲﹤鍚橀悢鍏兼優闂侇偅绋掗崑鍛磽閸屾瑨鍏岄柛瀣尭椤灝螣閼测晝鐓嬮梺姹囧灪閹爼鍩€椤掆偓閸熷瓨淇婇悜钘夌厸闁稿本鍩冮崑鎾绘倻閼恒儳鍘鹃梺鍛婄缚閸庢煡寮抽埡鍛厪闁糕剝锚婵秵鎱ㄦ繝鍕妺閻庣數鍘ч埢搴ㄥ箣閻樻﹫缍佸娲川婵犲啫鏆楅梺鍝ュТ闁帮綁銆佸鑸垫櫜濠㈣埖蓱閺呮繈姊洪棃娑氬婵炲眰鍔戣棟闁冲搫鎳忛埛鎴犵磽娴ｇ櫢渚涢柣鎺嶇矙閺屸剝鎷呯憴鍕偓鎰殽閻愬樊妯€妞ゃ垺宀搁崺鈧い鎺嗗亾妞?field query闂?     * 闂傚倸鍊搁崐鎼佸磹閻戣姤鍊块柨鏇楀亾妞ゎ亜鍟撮獮鎰償閿濆孩閿ら梻浣虹帛閸旀洟骞栭銈囩幓婵°倕鎳忛悡娆徝归崗鍏肩稇濞存粈鍗抽弻鈩冩媴閸涘妫＄紓浣虹帛缁诲啰鎹㈠┑瀣＜婵犲﹤鍠氶弶鎼佹⒒娴ｄ警鐒惧Δ鐘叉憸缁棁銇愰幒鎴ｆ憰濠电偞鍨崹褰掑础閹惰姤鐓忓┑鐐茬仢閸斻倕霉閻撳孩鍠樻慨濠冩そ瀹曨偊宕熼澶嬶紒婵犵數鍋涘鍓佸垝鎼粹垾锝夊箛閺夎法顔婂┑掳鍊撶粈浣圭瑜版帗鈷戠憸鐗堝俯閺嗘帡鏌ｉ幒鐐电暤闁诡噣绠栭幃婊堟嚍閵夈垺瀚肩紓鍌欑贰閸ㄥ崬煤濡　鏋嶉柛娑樼摠閻撴瑩鏌ц箛锝呪偓瀣敂閸偅鏅梺鎸庣箓椤︿粙寮崱娑欑厱闁哄洨鍋熸禒娑㈡煛閸滃啰鍒伴柍瑙勫灴閹晝绱掑Ο濠氭暘婵犵數鍋涢惇浼村磹濠靛棭鍤曢柕濞炬櫓閺佸洭鏌ｅΟ鍏兼毄闁?provider 濠电姷鏁告慨鐑藉极閹间礁纾婚柣鎰惈缁犳澘鈹戦悩宕囶暡闁稿骸绉电换婵囩節閸屾粌顣虹紓浣插亾闁告劏鏂傛禍婊堢叓閸ャ劍灏い蹇ｄ邯閺岋繝宕卞Δ鍐唶闂?deadline 闂傚倸鍊搁崐鎼佸磹閻戣姤鍊块柨鏇炲€归崕鎴犳喐閻楀牆绗掔紒鈧径灞稿亾閸忓浜鹃梺閫炲苯澧撮柛鈹惧亾濡炪倖甯婄粈渚€宕甸鍕厱闁规崘娉涢弸娑㈡煟濞戝崬娅嶇€规洖宕灒闁兼祴鏅濆Σ鍥⒒娴ｈ鍋犻柛搴灦瀹曟繄浠﹂崜褜娴勯梺闈涚箳婵厼銆掓繝姘厪闁割偅绻冮ˉ鐘差熆瑜滈崜鐔煎蓟閵堝牄浜归柟鐑樻⒒閺嗩偊鎮楀▓鍨珮闁稿锕ら悾閿嬬附缁嬪灝宓嗛梺缁樻煥閹碱偊鐛Δ鍛拻濞达絽鎲￠幆鍫ユ煕閻斿搫鈻堢€规洘鍨块獮妯尖偓娑櫭鎸庣節閻㈤潧孝闁稿﹨宕电划鏃堟惞閸忓浜炬繛鍫濈仢閺嬫稒銇勯鐐叉Щ闁伙絿鍏樺畷濂稿即閵婏附娅屽┑鐐舵彧缂嶁偓妞ゎ偄顦甸幃?     * 闂傚倸鍊搁崐鎼佸磹閹间礁纾瑰瀣椤愪粙鏌ㄩ悢鍝勑㈢紒鈧崼鐔虹闁糕剝蓱鐏忎即鏌涙繝鍛厫缂佺粯绻堝Λ鍐ㄢ槈閸楃偛澹堥梻?coordinator 闂傚倸鍊搁崐鎼佸磹妞嬪海鐭嗗〒姘ｅ亾妤犵偛顦甸弫鎾绘偐閼碱剦妲烽梻浣告惈濞层劍鎱ㄩ悜鑺ュ剹闁瑰墽绮悡鐔兼煏韫囨洖孝妞ゃ儱绻橀弻鈩冩媴缁嬫寧娈梺瀹狀潐閸ㄥ灝鐣烽幒鎴僵妞ゆ挾鍋炲▓姗€姊绘担铏瑰笡閻㈩垱甯￠垾锕傛倻閽樺鎽曢悗骞垮劚閻楁粌顬婇妸鈺傗拺闁告稑锕ョ亸鐢告煕閻樻煡鍙勯柟顕€绠栭幃婊堟嚍閵夈儰姹楅梺鑽ゅТ濞诧箒銇愰崘顔艰埞闁靛ň鏅滈崑鈩冪節婵犲倸鏆為柟鐧哥悼缁辨帡顢欓懖鈺佲叺闂佺硶鏂侀崑鎾愁渻閵堝棗绗掗悗姘煎弮閸╂盯骞嬮敂鐣屽幍闂佸吋绁撮弲娑欑濠婂牊鐓曢柡鍐ｅ亾鐎光偓閹间礁钃熼柣鏂挎惈閺嬪牓鏌涘Δ鍐ㄤ粧闁哥姴锕?quota 濠电姷鏁告慨鐢割敊閺嶎厼绐楁俊銈呭暞閺嗘粓鏌熼悜妯荤厸闁稿鎸搁～婵嬫偂鎼达紕顔愭俊鐐€ら崑鍕崲濡ゅ懎桅闁圭増婢樼粻鎶芥煙鐎电浠╃紒韬插灲濮婄粯鎷呴搹鐟扮濠殿喖锕ら…宄扮暦閵忥綆妯佺紓?     */
    private ResolvedFieldEvidenceQueryPlan resolveExecutableFieldEvidenceQueries(CollectorNodeConfig config,
                                                                                 long baseSearchTimeoutMillis) {
        List<FieldEvidenceQuery> planned = resolveFieldEvidenceQueries(config);
        if (planned == null || planned.isEmpty()) {
            return ResolvedFieldEvidenceQueryPlan.empty();
        }
        FieldEvidenceQueryExecutionPlan executionPlan = fieldEvidenceQueryExecutionGate.resolve(
                planned,
                searchPolicyResolver.resolveFieldEvidenceMaxQueriesPerField(),
                searchPolicyResolver.resolveFieldEvidenceMinThirdPartyQueriesPerField(),
                searchPolicyResolver.resolveFieldEvidenceMaxQueriesPerNode()
        );
        return ResolvedFieldEvidenceQueryPlan.from(executionPlan);
    }
    /**
     * 闂傚倸鍊搁崐椋庣矆娴ｉ潻鑰块梺顒€绉甸崑锟犳煙閹増顥夋鐐灲閺屽秹宕崟顐熷亾瑜版帒绾?deadline 闂?search 闂傚倸鍊搁崐宄懊归崶顒夋晪闁哄稁鍘肩粈鍫熺箾閸℃ɑ灏ㄩ柍褜鍓ㄧ粻鎾诲箖濠婂嫭鍙忛柟鑸妼娴滈箖鏌涘畝鈧崑娑㈡偂濞戙垺鐓曢柟鏉垮悁缁ㄩ绱掑Δ鈧ˇ顖炲煘閹寸偛绠犻梺绋匡攻閸旀瑥鐣烽幋锕€绠绘繛锝庡厸缁ㄥ姊洪幐搴⑩拻闁哄拋鍋婂畷锝夊焵椤掑嫭鈷戦悹鍥ｂ偓铏亞缂備緡鍠楅悷锔界┍婵犲洤绠瑰ù锝堝€介妸鈺傜叆闁哄洦顨呮禍楣冩⒑闁偛鑻晶顔锯偓瑙勬处閸撶喖宕洪妷锕€绶為柟閭﹀墰椤旀帒顪冮妶鍡欏闁活収鍠楃粩鐔煎即閵忊檧鎷绘繛杈剧到閹诧繝宕悙鐑樼厽闁绘棁顔婇崥顐も偓鍨緲閿曨亪骞冮崜褌娌紓浣靛灩娴犳椽姊绘担铏瑰笡闁告梹顨婂畷鏇㈠Χ婢跺﹦鏌у銈嗗笒閸婄敻宕戦幘璇茬濠㈣泛锕ｆ竟鏇㈡⒒娴ｅ憡鍟炴繛璇ч檮缁傚秹鎮欓崹顐綗濠殿喗顭堥崺鏍煕?provider 婵犵數濮烽弫鎼佸磻閻愬搫绠板┑鐘崇閸庡秵绻濇繝鍌滃缂佲偓鐎ｎ偁浜滈柟鎵虫櫅閳ь剚鐗犲畷顖炲Ω閳哄倵鎷绘繛杈剧到閹诧繝宕悙鐑樺仺妞ゆ牗渚楀▓鏇㈡煕閹烘埊鏀荤紒鍌涘笧閳ь剨绲芥晶搴ｇ矙韫囨稒鈷戦柟绋垮缁€鈧梺绋匡工閹芥粎妲愰幒妤€鐓涢柛娑卞枤閸橀潧顪冮妶鍡欏ⅹ婵☆偅鏌ㄩ—鍐箳閹炽劌缍婇幃婊堟嚍閵夈垺瀚兼繝娈垮枤閹虫挸煤閵堝棔绻嗗┑鍌氭啞閸婂灚鎱ㄥΟ鐓庡付闁诲骏绲跨槐鎺楊敊閼恒儺妫冨Δ鐘靛仦閿曘垽銆佸▎鎾村殐闁冲搫鍟紞渚€姊婚崒娆戭槮闁硅绻濋獮鎰版倻閼恒儱娈戦柣鐘荤細濞咃綁寮抽敃鍌涚厱妞ゆ劧绲剧粈鍐煟閹惧瓨绀冪紒缁樼洴瀹曞崬螣閸忕厧娅樼紓鍌欐閼冲爼宕楀鈧?     */
    private Long resolveFieldEvidenceExecutionDeadlineEpochMillis(long searchTimeoutMillis,
                                                                  ResolvedFieldEvidenceQueryPlan fieldEvidenceQueryPlan) {
        if (fieldEvidenceQueryPlan == null
                || fieldEvidenceQueryPlan.getExecutable() == null
                || fieldEvidenceQueryPlan.getExecutable().isEmpty()) {
            return null;
        }
        if (searchTimeoutMillis < 0L) {
            return null;
        }
        return System.currentTimeMillis() + searchTimeoutMillis;
    }

    /**
     * 缂傚倸鍊搁崐鎼佸磹閹间礁纾归柟闂寸绾惧綊鏌ｉ幋锝呅撻柛銈呭閺屾盯顢曢敐鍡欘槬缂備焦鍔栭〃鍫ュ焵椤掆偓缁犲秹宕曢崡鐏绘椽濡搁埡浣侯攨濠殿喗顭堥崺鏍磹閻㈠憡鈷掗柛顐ゅ枔閳洟鏌涢悢鍝勪槐闁哄本绋撻埀顒婄秵閸嬫捇鎳撻崸妤佺厸閻忕偟鍋撶粈鍐偓鍨緲鐎氭澘鐣烽崡鐐嶇喖鎳￠妶鍛偧缂傚倸鍊搁崐椋庣矆娓氣偓钘濋梺顒€绉寸粈鍌涙叏濡炶浜鹃悗娈垮枦椤曆囧煡婢跺ň鏋庨柟瀵稿Х濡插洭姊绘担鍛婂暈闁告棑闄勭粋宥呪攽鐎ｎ亪妫锋繛瀵稿帶閻°劑鍩?providerKey闂傚倸鍊搁崐鎼佸磹閻戣姤鍊块柨鏃堟暜閸嬫挾绮☉妯诲櫧闁活厽鐟╅弻鐔衡偓鐢殿焾娴犙囨⒒閸曨偄顏柡宀嬬節瀹曟﹢濡搁妷銏犱壕闁荤喐澹嗛弳锕傛煕濞嗗浚妲归柛娆忕箲娣囧﹪顢涘顓炰淮闂佸憡绻冨浠嬪箖濡も偓椤繈顢橀垾鎰佹闂傚倸娲らˇ鐢稿蓟閵娿儮鏀介柛鈩兠▍銈夋⒑閸濄儱鏋旈柛瀣ㄥ€濆璇测槈濞嗘垹鐦堥梺鍛婃处閸撴艾袙閸曨厾纾藉ù锝勭矙閸濇椽鏌熷灞藉惞缂侇噮鍙冮幃銏ゆ偂鎼达絽鈧偤鎮峰鍐ら柣姘劤椤撳吋寰勭€Ｑ勫闂傚倸鍊搁悧鍐疾濠靛牃鍋撻棃娑栧仮闁哄本绋戣灃闁逞屽墴瀹曨垶宕稿Δ瀣◤闂婎偄娲︾粙鎴︽煥閵堝棔绻嗛柕鍫濆€告禍鎯旈悩闈涗杭闁搞劍妞介獮鍫ュΩ閵夊海鍠愬鍕矙閹稿骸鍓甸梻鍌欑閹芥粓宕戦悙鍝勭闁告劕妯婂鏍煕濠靛棗鈻曢柧蹇撴贡绾惧吋淇婇婵嗕汗闁绘稏鍨荤槐鎾诲磼濞嗘埈妲梺鍏兼た閸ㄥ爼骞冮妷锔鹃檮缂佸瀵уΣ顒勬⒑闁偛鑻晶顖炴煏閸パ冾伃妤犵偛顑夐弫鎰板幢濞嗗秮鍋撴繝鍌ゆ富闁靛牆绻楅娲⒒閸曨偄顏┑锛勬暬瀹曠喖顢涘槌栧晪闂備焦鍎冲ù姘跺磻閸涙潙绐楅柟鎵閳锋垿鏌ｉ悢鍛婄凡婵¤尙绮妵鍕箣濠垫劖鈻堥悗瑙勬礉椤濡堕敐澶婄闁宠桨鐒﹂缁樹繆閻愵亜鈧牜鏁幒妤€纾圭憸鐗堝笒缁愭绻涢幋娆忕仾闁抽攱甯掗湁闁挎繂鐗滃鎰版煕鐎ｎ剙鈻堥柡灞剧⊕閹棃濮€閻橆偅鐏嗛柣搴ゎ潐濞叉﹢宕归崸妤€绠栨繛鍡樻尭娴肩娀鏌涢弴銊ュ⒒婵☆偆鍠庨埞鎴︽倷鐎涙ê闉嶉梺绯曟櫅閸熸潙鐣烽幋锕€绠荤紓浣诡焽閸樻悂鏌ｈ箛鏇炰户闁哄拋鍋呴弲鍫曟晜闁款垰浜鹃悷娆忓缁€鍐煕閺冣偓閻熲晠鐛崘顔碱潊闁绘ê鐏氬▓婵嬫⒑閸濆嫷妲兼繛澶嬫礋椤㈡﹢鎮滃Ο鑲╃槇闂佹眹鍨藉褎绂掗敃鍌涚厵婵繂鑻崥褰掓煕閻樿宸ユい鎾炽偢瀹曞爼濡搁妷銉у搸闂傚倷鑳剁涵鍫曞礈濠靛鍋＄憸鏃堝箚鐏炴儳绶為柟閭﹀幘閸橀亶姊洪弬銉︽珔闁哥喍鍗抽獮濠囧川鐎涙鍘藉銈嗘尵閸犳捇骞婇崶顒佺厸閻忕偛澧藉ú瀛橆殽閻愬弶鍠樻い銏＄☉椤繃娼忛…鎴濇畱闂傚倸鍊风粈渚€骞栭鈶芥盯寮崼婵堫攨闂佸憡鍔曞顒€鈽夐姀鐘茬獩闂佸湱鈷堥崢浠嬪疾閵忥紕绡€闁靛骏绲剧涵楣冩煥閺囶亞鎮奸柤娲憾閹粙宕ㄦ繛鐐闂備礁鎲＄缓鍧楀磿鏉堛劎顩插┑鍌氭啞閻撴洖鈹戦悩鎻掓殶缂佺姵鐗曡彁?     */
    private String resolveProviderKey(SourceCandidate candidate, String stage) {
        if (candidate != null && StringUtils.hasText(candidate.getProviderKey())) {
            return candidate.getProviderKey();
        }
        if ("HTTP".equalsIgnoreCase(stage)) {
            return "http";
        }
        if ("BROWSER".equalsIgnoreCase(stage)) {
            return "browser";
        }
        if ("BOOTSTRAPPED".equalsIgnoreCase(stage)) {
            return "tavily";
        }
        return "planned";
    }

    /**
     * 闂傚倸鍊搁崐椋庣矆娓氣偓楠炴牠顢曚綅閸ヮ剦鏁冮柨鏇楀亾闁汇倗鍋撶换婵囩節閸屾粌顣虹紓浣插亾濠㈣泛顑嗛崣蹇斾繆閻愰鍤欏ù婊堢畺濮婃椽妫冨☉娆樻缂備浇鍩栧畝鎼佹偘椤旈敮鍋撻敐搴℃灍闁哄懏绻堥弻宥堫檨闁告挻鐩崺鈧?public search 闂傚倸鍊搁崐鎼佸磹妞嬪海鐭嗗〒姘ｅ亾妤犵偛顦甸弫宥夊礋椤撶姷鍘梻浣告贡閸庛倝銆冮崨鏉戝瀭闁稿本绋撶弧鈧梻鍌氱墛娓氭宕曢幇鐗堢厱闁靛牆鎳愭晶锕傛煛瀹€鈧崰鎰焽韫囨稑绀堢憸蹇涘汲閻樼粯鈷戦柛娑橈工婵偓闂佺顑嗛幑鍥ь潖缂佹ɑ濯撮柦妯侯槸閹偤姊洪崫銉バｉ柛鏃€鐗滅划鈺呮偄閻撳骸鑰垮┑鐐村灦閿氶柛瀣Ч濮婂搫效閸パ呬患闂佺顕滅换婵嬪箖閳ユ枼鏋庨柟鐐綑閳ь剙鐏氱换娑㈠箣閻愬灚鍣介梺缁樺笩濡嫰鈥﹂懗顖ｆ婵炲瓨绮犳禍顏勵嚕鐠囨祴妲堟俊顖炴敱閻庡妫呴銏″婵☆偄绻愯灋闁挎洖鍊归崐鐢告偡濞嗗繐顏紒宀冩硶缁辨挸顓奸崟顓犵崲閻庢鍣崑濠囩嵁閸ヮ剦鏁囬柣鎰暩閻涱噣姊绘担绋款棌闁稿鎳橀幊婵嬫倷椤掑偆娲搁梺璇″瀻瀹€鈧崬鐢告⒑閸忓吋鍊愭繛浣冲嫭鍙忛柛銉墯閸嬧剝绻涢崱妯兼噮闁伙絽鐏氶〃銉╂倷鐎电鈷屽Δ鐘靛仦閻楃娀銆佸鈧幃娆撳礂缁嬪灝顕х紓鍌氬€搁崐椋庢媼閺屻儱纾婚柟鐐墯閻斿棝鏌ら幖浣规锭濠殿喖鐗撻弻锝夊箻鐎涙顦伴梺鍝勭灱閸犳牠寮婚崶顒佹櫇闁逞屽墯閺呰埖瀵奸弶鎴濆敤闂佹枼鏅涢崯鎵姬閳ь剛绱掗崜褍顣奸柨姘熆瑜庨悡锟犲蓟閻旂⒈鏁婇柣鎾崇岸閸嬫捇骞栨笟鍥ㄦ櫔闂佹寧绻傚Λ娑€呴悜鑺ュ€甸柨婵嗛娴滄粌霉閻欌偓閸欏啫顫忛搹瑙勫枂闁告洟娼ч弲閬嶆⒑閸濄儱校闁绘濞€閹即顢欑喊鍗炴倯婵犮垼娉涢鍌炲箯濞差亝鈷戠痪顓炴噹娴滃綊鏌涚€ｎ偆娲寸€规洘绻堟俊鑸靛緞鐎ｎ剙骞堥梺璇插嚱缂嶅棝宕滃▎鎾冲嚑闁瑰濮风壕濂告煕鐏炵偓鐨戦懖鏍⒑闁偛鑻晶瀛樼箾娴ｅ啿娲ょ粻鐑樼節婵犲倹鍣规い?direct discovery闂?     * 闂傚倸鍊搁崐宄懊归崶褏鏆﹂柣銏㈩焾绾惧鏌ｉ幇顔芥毄闁活厽鐟╅悡顐﹀炊閵娧€妲堢紒鐐劤濞硷繝寮婚敐澶婎潊闁靛繆鍓濆В鍕磽娴ｆ彃浜?docs/pricing/help/open 缂傚倸鍊搁崐鎼佸磹閹间礁纾归柣鎴ｅГ閸婂潡鏌ㄩ弬鍨挃闁活厽鐟╅弻鐔封枎闄囬褍煤椤撱垻宓侀煫鍥ㄦ磻濞岊亪鏌ら幁鎺戝姕妞ゆ柨顦靛濠氬磼濮橆兘鍋撴搴ｇ焼濞撴埃鍋撴鐐寸墵椤㈡洑缍呴柛銉墻閺佸秹鏌ｉ幇顓熺稇闁逞屽墲閸╂牜鎹㈠┑瀣棃婵炴垵宕崜浼存⒑濞茶骞楁い銊ワ躬瀵鈽夐姀鐘靛姶闂佸憡鍔︽禍鏍ｉ崼銉︹拺婵炶尪顕ч獮妤併亜閵娿儻韬€殿喖顭锋俊鎼佸Ψ閵忊剝鏉搁梻浣虹《閸撴繈鎮疯椤㈡瑦绻濋崟顓狅紳婵炶揪绲捐ぐ鍐╃閻愵剛绡€闁靛骏绲介悡鎰版煕閺冣偓閻楃娀宕哄☉銏犵闁绘劦浜欑花濠氭⒑閻熺増鎯堟俊顐ｎ殕缁傚秵銈ｉ崘鈹炬嫼闁荤姴娲╃亸娆戠不閼碱剛纾奸悹鍥皺婢ф洟鏌ｉ敐鍥у幋濠殿喒鍋撻梺闈涚墕閹叉顦叉い顓℃硶閹瑰嫰鎮弶鎴濐潬濠电偛顕崢褔顢栭崨鏉戠厴闁硅揪闄勯崑鎰版偣閸ュ洤鍟╃槐锝夋⒑鐠囨彃顒㈤柛鎴濈秺瀹曪綁宕橀妸褎娈鹃梺鐟扮摠缁洪箖寮ㄦ禒瀣€甸柨婵嗛娴滅偤鏌涘鈧禍璺侯潖濞差亜鎹舵い鎾跺仜婵″搫顪冮妶鍐ㄥ缂佺粯锚椤曪綁骞庨挊澶岊唺闂佸搫鍟崐濠氭晬濠婂喚娓婚柕鍫濋楠炴鎮介婊冧户濠㈣娲熷畷绋课旀担鍝勫笚闁荤喐绮嶇划鎾崇暦濠婂啠鏀介悗锝呭缁嬪繑绻濋姀锝呯厫闁告柨绻橀崺鈧い鎺戯功閻ｇ數鈧娲栭悥濂稿春閿熺姴绀冩い蹇撶У绗戝┑鐘垫暩婵即宕归悡搴樻灃婵炴垯鍩勯弫鍕煕閳╁厾顏堝垂閺冨牊鐓欑紓浣靛灩閺嬬喖鏌ｉ幘瀛樼闁哄苯绉归崺鈩冩媴閸涘﹥顔夌紓鍌欒閸嬫捇鏌涢鐘插姕闁绘挾鍠栭弻锝夊棘閹稿孩鍠愮紓浣哄У閹瑰洤螞娴ｇ懓绶為柟閭﹀幘閸橀亶妫呴銏″闁瑰憡鎮傞獮鍐箣閿旂晫鍘介梺闈涱煭缁犳垿鎮橀幘顔界厸鐎光偓閳ь剟宕伴弽顓犲祦婵せ鍋撴い銏″哺瀹曘劑顢氶崨顕呮缂傚倸鍊搁崐宄懊归崶銊ｄ粓闁告縿鍎查弳婊堟煥閻斿搫袨闁逞屽厸缁€渚€锝炲鍫濈劦妞ゆ帒瀚粻鏍煏韫囷絾绶氶柣鎺戯工闇夐柨婵嗘处閸も偓闂佷紮绠戦悥鐓庮潖缂佹ɑ濯寸紒娑橆儏濞堫厾绱撴担铏瑰笡閻㈩垪鈧磭鏆﹂柟杈鹃檮閸婄兘鎮锋担椋庮槮闁圭⒈鍋婇、姗€宕楅悡搴ｇ獮婵犵數濮寸€氼剚鎱ㄩ敂鎴掔箚闁绘劦浜滈埀顒佺墱閺侇喗绻濋崶銊ユ畱闂佸憡鎸烽懗鑸电▔瀹ュ棔绻嗘い鏍ㄧ箓閸氬湱绱掗悩鑽ょ暫闁哄本鐩、鏇㈠Χ閸涱喚鈧姊虹拠鑼妞ゆ洦鍙冮崺鈧い鎺嶇贰閸熷繘鏌涢敐搴℃珝鐎规洘绮撻幃銏㈢箔鐞涒€充壕濞达絽澹婂銊╂煃瑜滈崜鐔肩嵁閸愵喖鐓涢柛娑卞灠瑜板嫬顪冮妶鍡樺暗闁稿鐩、姘愁樄婵?     */
    private List<SourceCandidate> expandSearchCandidatesThroughDirectDiscovery(CollectorNodeConfig config,
                                                                               List<SourceCandidate> searchCandidates,
                                                                               List<SourceCandidate> existingCandidates) {
        if (config == null || searchCandidates == null || searchCandidates.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> existingUrls = new LinkedHashSet<>();
        for (SourceCandidate existingCandidate : existingCandidates == null ? List.<SourceCandidate>of() : existingCandidates) {
            SourceCandidate normalizedCandidate = normalizeCandidateCanonicalUrl(existingCandidate);
            if (normalizedCandidate != null && StringUtils.hasText(normalizedCandidate.getUrl())) {
                existingUrls.add(normalizedCandidate.getUrl());
            }
        }
        LinkedHashSet<String> rootUrls = new LinkedHashSet<>();
        for (SourceCandidate candidate : searchCandidates) {
            SourceCandidate normalizedCandidate = normalizeCandidateCanonicalUrl(candidate);
            if (normalizedCandidate != null && existingUrls.contains(normalizedCandidate.getUrl())) {
                continue;
            }
            if (!shouldExpandSearchCandidateThroughDirectDiscovery(config, candidate)) {
                continue;
            }
            String rootUrl = toRootUrl(candidate == null ? null : candidate.getUrl());
            if (StringUtils.hasText(rootUrl)) {
                rootUrls.add(rootUrl);
            }
        }
        if (rootUrls.isEmpty()) {
            return List.of();
        }
        return directDiscoveryPlanner.buildInitialCandidates(
                        config.getCompetitorName(),
                        safeSourceType(config.getSourceType()),
                        new ArrayList<>(rootUrls)
                ).stream()
                .filter(candidate -> candidate != null
                        && !"DIRECT_LOCATOR".equalsIgnoreCase(candidate.getDiscoveryMethod()))
                .map(candidate -> {
                    SourceCandidate normalizedCandidate = normalizeCandidateCanonicalUrl(candidate);
                    if (normalizedCandidate == null) {
                        return null;
                    }
                    return normalizedCandidate.toBuilder()
                            .discoveryMethod("SEARCH_ROOT_TEMPLATE")
                            .reason("search result root expanded through direct discovery templates")
                            .relevanceScore(0.74D)
                            .freshnessScore(0.55D)
                            .qualityScore(0.80D)
                            .sourceUrls(resolveSearchExpansionSourceUrls(normalizedCandidate, searchCandidates))
                            .build();
                })
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    /**
     * 闂?direct discovery 闂傚倸鍊搁崐鎼佸磹妞嬪海鐭嗗ù锝夋交閼板潡姊洪鈧粔鏌ュ焵椤掆偓閸婂湱绮嬮幒鏂哄亾閿濆簼绨介柡灞熷啠鏀介柣鎰綑閻忕喖鏌涢妸銉хШ鐎规洏鍎抽幉鎾礋閳衡偓缁ㄥ姊虹憴鍕姢鐎规洦鍓熼幃姗€鍩￠崘顏嗭紲闂佺粯鐟﹂悷銉ッ洪敃鍌涘亗闊洦鎼╅悢鍡涙偣閸ワ絺鍋撳畷鍥﹀摋闂佽瀛╅崙褰掑闯閿濆拋鍤曢柟鎯板Г閸嬫劗绱撴担楠ㄦ岸骞忛搹鍦＝濞达絽澹婇崕蹇涙倶韫囨挻鍤囩€殿喓鍔嶇换婵嗩潩椤撶姴骞楅梻浣虹帛閺屻劌顕ｇ捄琛℃瀺濠电姴娲﹂悡鏇㈡煃鐟欏嫬鍔ゅù婊呭亾娣囧﹪鎮欓鍕ㄥ亾閺嵮屽晠濠电姵鑹剧壕濠氭煙閻愵剛鏆樺ù婊勭矒閺屻劑寮崶璺烘闂佽绻掓繛鈧柟顕嗙節婵¤埖寰勭€ｎ剙骞愰柣搴＄畭閸庤鲸顨ラ幖浣哄祦闁哄稁鍋嗙壕濂告煟濡搫鑸圭€规挸妫濋弻锛勪沪閸撗勫垱婵犵绱曢崗姗€鐛€ｎ亖鏀介柛鈩兩戦宥夋⒒娴ｅ憡鍟為拑閬嶆偨椤栥倗绡€鐎殿喖顭烽弫鎰緞鐎ｎ亙绨婚梻浣告啞缁哄潡宕曢弻銉ュ惞闁稿本绮庣壕钘壝归敐鍛儓閺嶏繝姊洪幖鐐插闁靛牆鎲℃穱濠囨偨缁嬭法鐤€闂佸搫顦冲▔鏇㈡晬濠婂啠鏀介柣妯荤懃鐎氼剟宕濋妶鍚ょ懓顭ㄩ崼銏㈡毇闂佸搫鐭夌紞渚€骞冮姀銈呭窛濠电姴瀚崵鎺楁⒒娴ｅ憡鎯堟俊顐ｇ洴瀹曚即骞囬钘夊簥濠电偞鍨崹褰掓煁閸ヮ剚鐓熼柡鍐ㄧ墱濡垵霉閻撳氦瀚伴摶鏍煟濮椻偓濞佳勭閿曞倹鐓熸俊銈勭劍缁€瀣煃閵夘垳鐣靛┑鈩冩倐閸┾剝鎷呴崫銉у春濠碉紕鍋戦崐鏍箰妤ｅ啫纾绘慨妞诲亾妤犵偛顦靛畷婊嗩槾缁惧彞绮欓弻娑氫沪閹规劕顥濋梺閫炲苯澧伴柛蹇旓耿楠炲啴鎮欓悜妯绘珖闂佺鏈銊╊敊閸ャ劎绡€闁汇垽娼ф牎缂佺偓婢樼粔鐟扮暦?URL闂?     * 闂傚倸鍊搁崐椋庣矆娓氣偓楠炴牠顢曚綅閸ヮ剦鏁冮柨鏇楀亾闁汇倗鍋撶换婵囩節閸屾粌顤€闂佺顑戠换婵嬪蓟閵娾晛鍗抽柣鎰ゴ閸嬫捁銇愰幒鎴狅紱?sourceUrls 闂傚倸鍊搁崐鎼佸磹妞嬪海鐭嗗〒姘ｅ亾妤犵偞鐗犻、鏇㈡晝閳ь剟鎮块鈧弻鏇熺箾閻愵剚鐝旈梺鎼炲妼閸婃悂鍩為幋锕€纾兼繝濠傛捣閸斿摜绱撴担鎻掍壕闂佺鏈粙鎰崲閸℃ǜ浜滈柡宥冨姀婢规﹢鏌熼钘夌伌闁诡喗顨呴～婵嬵敇閻愬弶鎳欓梻浣筋嚃閸犳銆冮崨鏉戠叀濠㈣泛艌閺嬪秹鏌ц箛锝呬簻闁诲繑鎸抽弻銊モ攽閸繀妲愰悗娈垮枙缁瑩銆佸鈧幃銏ゅ传閸曨偆鐟查梻鍌氬€风欢姘焽瑜旂瘬闁逞屽墮閳规垿鍨鹃搹顐㈡灎闂佽鍨伴惉濂稿焵椤掑﹦绉甸柛鐘愁殜閹€斥枎閹扳晙绨婚梺鍝勫暙濞层倖绂嶈ぐ鎺撶叆婵炴垶顭囨牎婵烇絽娲ら敃顏勭暦閿濆棗绶炲┑鐘插亞濞兼岸姊绘担鐟邦嚋缂佽瀚板畷鎴﹀Χ婢跺牃鍋撴担鍓叉建闁逞屽墴楠炲啴鍩￠崨顔间缓闂傚倸鐗婄粙鎴犵不婵犳碍鈷掗柛灞捐壘閳ь剟顥撶划鍫熺瑹閳ь剟鐛径鎰櫖闁告洦鍓欐惔濠囨倵楠炲灝鍔氭俊顐ｇ洴閵嗗懘宕ｆ径宀€鐦堥梻鍌氱墛缁嬫帡鏁嶅澶嬬厽闁瑰搫绉堕惌娆撴煛瀹€瀣М濠殿喒鍋撻梺闈涚箚閺呮繈宕濋幖浣光拺閻犲洩灏欑粻鐑樼箾閸涱喗绀堥柟骞垮灩閳规垹鈧綆浜滈悗顓烆渻閵堝棗濮х紒韫矙瀵啿螖閸愵亞锛濇繛杈剧稻瑜板啯绂嶉悙顒傜瘈闁靛骏绲剧涵楣冩嚌鐏炲彞绻嗛柟缁樺笧婢э箓鏌″畝瀣瘈鐎规洘锕㈡俊鎼佸Ψ閵忕姳澹曢梺褰掓？缁€渚€宕欓悩宕囩闁糕剝蓱鐏忎即鏌ｉ幘瀛樼闁绘搩鍋婂畷鍫曞Ω閿旈敮鍋撴總鍛婄厵閻庣數顭堝暩闂佹椿鍘藉畝鎼佸蓟濞戞鏃堝礃閵娿倖鐫忛梻浣姐€€閸嬫挸霉閻樺樊鍎愰柣鎾存礃閵囧嫰骞囬埡浣插亾閺囥垹鍑犻柟杈鹃檮閻撶喖鏌ㄥ┑鍡涱€楀褍鐡ㄩ幈銊︾節閸愨斂浠㈤悗瑙勬处閸嬪﹤鐣烽悢纰辨晣闁绘垵妫欏▓濂告⒒閸屾瑨鍏屾い顓炵墦椤㈡牠宕卞☉妯碱唶闂佸憡鎸嗛崟鍨稐闂備浇顫夐崕鎶芥偤閵婏箑鍨旈柟缁㈠枟閻撴洘绻濋棃娑橆仼闁告梹纰嶉妵鍕晲閸℃ǜ浠㈠┑顔硷攻濡炶棄鐣烽妸锔剧瘈闁告洦鍓欏▍鎴炵節绾版ɑ顫婇柛瀣噽閹广垽宕掗悙鏉戜患闂佺粯鍨兼慨銈夊疾閹绘帩鐔嗛悹杞拌閸庢劖绻涢崨顔剧煉婵﹥妞介獮鏍倷閹绘帒顫戦梻浣告啞閺屻劑鏌婇敐鍜佸殨闁规儼濮ら崑鎰磽娴ｉ姘跺箯濞差亝鈷戦柛娑橈功閳藉鏌ㄩ弴顏嗙暤妤犵偛锕獮鍥偋閸垹骞堥梻渚€娼ц噹闁告洦鍓氶惁鎾翠繆閵堝洤啸闁稿绋撻幑銏ゅ箛閻楀牆浠奸梺璺ㄥ枔婵绮婚妷鈺傜叄闊浄绲芥禍婵嬫煛閸℃鏀诲ǎ鍥э躬閹瑩顢旈崟銊ヤ壕闁哄稁鍘介崑瀣繆閵堝懎鏆熼柣顓熺懇閺屾盯顢曢悩鎻掑缂佺偓鍎抽…鐑藉蓟閻旂厧绠查柟浼存涧濞堟劕鈹戦埄鍐ㄧ祷缂傚秴锕ら～?     */
    private List<String> resolveSearchExpansionSourceUrls(SourceCandidate expandedCandidate,
                                                          List<SourceCandidate> searchCandidates) {
        LinkedHashSet<String> sourceUrls = new LinkedHashSet<>();
        String expandedDomain = canonicalUrlResolver.canonicalDomain(
                expandedCandidate == null ? null : expandedCandidate.getUrl()
        );
        for (SourceCandidate searchCandidate : searchCandidates == null ? List.<SourceCandidate>of() : searchCandidates) {
            if (searchCandidate == null || !StringUtils.hasText(searchCandidate.getUrl())) {
                continue;
            }
            String searchDomain = canonicalUrlResolver.canonicalDomain(searchCandidate.getUrl());
            if (isSameSearchExpansionDomain(expandedDomain, searchDomain)) {
                sourceUrls.add(searchCandidate.getUrl());
            }
        }
        if (sourceUrls.isEmpty() && expandedCandidate != null && expandedCandidate.getSourceUrls() != null) {
            sourceUrls.addAll(expandedCandidate.getSourceUrls());
        }
        if (sourceUrls.isEmpty() && expandedCandidate != null && StringUtils.hasText(expandedCandidate.getUrl())) {
            sourceUrls.add(expandedCandidate.getUrl());
        }
        return new ArrayList<>(sourceUrls);
    }

    private boolean isSameSearchExpansionDomain(String expandedDomain, String searchDomain) {
        if (!StringUtils.hasText(expandedDomain) || !StringUtils.hasText(searchDomain)) {
            return false;
        }
        String normalizedExpandedDomain = expandedDomain.toLowerCase(Locale.ROOT);
        String normalizedSearchDomain = searchDomain.toLowerCase(Locale.ROOT);
        return normalizedExpandedDomain.equals(normalizedSearchDomain)
                || normalizedExpandedDomain.endsWith("." + normalizedSearchDomain)
                || normalizedSearchDomain.endsWith("." + normalizedExpandedDomain);
    }

    private List<String> resolveSearchFallbackOrder(CollectorNodeConfig config) {
        List<String> resolvedOrder = searchPolicyResolver.resolveFallbackOrder(
                config.getSearchMode(),
                Boolean.TRUE.equals(config.getBrowserSearchEnabled()),
                config.getSearchFallbackOrder()
        );
        if (!hasPendingFieldEvidenceQueries(config)
                || !resolvedOrder.contains("HTTP")
                || !resolvedOrder.contains("BROWSER")) {
            return resolvedOrder;
        }
        List<String> reordered = new ArrayList<>();
        for (String stage : resolvedOrder) {
            if ("BROWSER".equals(stage)) {
                continue;
            }
            reordered.add(stage);
            if ("HTTP".equals(stage)) {
                reordered.add("BROWSER");
            }
        }
        return reordered;
    }

    private BrowserSearchRuntimeResult defaultBrowserSupplementResult(CollectorNodeConfig config) {
        if (!Boolean.TRUE.equals(config.getBrowserSearchEnabled())) {
            return BrowserSearchRuntimeResult.builder()
                    .candidates(List.of())
                    .executedQueries(List.of())
                    .summary("browser supplement disabled; HTTP fallback remains available")
                    .fallbackSuggested(true)
                    .blockedCount(0)
                    .build();
        }
        return BrowserSearchRuntimeResult.builder()
                .candidates(List.of())
                .executedQueries(List.of())
                .summary("planned candidates only")
                .fallbackSuggested(false)
                .blockedCount(0)
                .build();
    }

    private String resolveEmptySupplementDecision(boolean browserModeEnabled,
                                                  boolean httpModeEnabled,
                                                  boolean browserExecuted,
                                                  boolean httpExecuted) {
        if (!browserModeEnabled && !httpModeEnabled) {
            return "SEARCH_MODE_KEEP_PLANNED";
        }
        if (!browserModeEnabled) {
            return httpExecuted ? "BROWSER_DISABLED_KEEP_PLANNED" : "SEARCH_MODE_KEEP_PLANNED";
        }
        if (!httpModeEnabled && browserExecuted) {
            return "BROWSER_ONLY_KEEP_PLANNED";
        }
        return "NO_NEW_CANDIDATES_KEEP_PLANNED";
    }

    private int resolveSupplementTargetPoolSize(CollectorNodeConfig config,
                                                boolean resultPageVerificationEnabled,
                                                int currentCandidateCount,
                                                int verifiedCount,
                                                int minVerifiedCount,
                                                int targetCount) {
        if (Boolean.TRUE.equals(config.getVerifyCandidates()) && resultPageVerificationEnabled) {
            int requiredNewCandidates = Math.max(1, minVerifiedCount - verifiedCount);
            return currentCandidateCount + requiredNewCandidates;
        }
        /*
         * search-first direct seed 婵犵數濮烽弫鍛婃叏閻戝鈧倿鎸婃竟鈺嬬秮瀹曘劑寮堕幋婵堚偓顓烆渻閵堝懐绠伴柣妤€妫濋幃鐐哄垂椤愮姳绨婚梺鐟版惈濡绂嶉崜褏纾奸柛鎾楀棙顎楅梺鍛娚戦崕鎶藉煡婢舵劖鍋ㄧ紒瀣硶閸旓箑顪冮妶鍡楃瑐闁煎啿澧庣划缁樸偅閸愨晝鍘甸柣搴ｆ暩椤牊绂掗敃鍌涘€堕煫鍥风到楠炴鏌曢崶褍顏鐐差儔閹瑩鎳犻鎸庡亝濠电姷顣介埀顒€鍟跨痪褔鏌涢弮鈧ú鐔煎灳閿曗偓閻ｇ兘宕堕埡鍐幇濠电娀娼чˇ閬嶎敋閻хCount 闂傚倸鍊峰ù鍥敋瑜庨〃銉╁箹娴ｇ懓鈧埖鎱ㄥ鍡楀箻闁绘繂鐖奸幃妤呮晲鎼粹剝鐏堢紒鐐劤椤兘寮婚悢鐓庣畾鐟滃繑绂嶉鍫熺厱閻庯綆鍋呭畷宀€鈧娲栫紞濠囥€侀弴銏狀潊闁冲搫鍋嗗Λ鎴︽⒒閸屾瑨鍏屾い顓炵墦椤㈡牠宕卞☉妯碱唹闂佹悶鍎滅仦鑺ヮ吙闂備礁澹婇崑鍛洪弽顓熷亗婵炴垯鍨洪悡鏇㈡倵閿濆簼绨藉ù鐘崇洴閺屾盯濡搁埡鍌涢敪閻庤鎸哥€氭澘顫忔ウ瑁や汗闁圭儤鎼槐鐢告⒑缂佹ê閲滅紒鐘虫尭閻ｇ兘骞嬮敃鈧儫闂佸啿鎼崐濠氬储椤忓懐绡€闁汇垽娼у瓭缂備胶绮敮锟犲箖妤ｅ啯鐒肩€广儱妫涢崢浠嬫⒑鐟欏嫬鍔ら柣掳鍔庣划鍫⑩偓锝庡枟閻撴稓鈧厜鍋撻悗锝庡墰閻﹀牓鎮楃憴鍕闁哥姵鐗犻妴浣肝旈崨顓犲姦濡炪倖甯掔€氼剛绮堟径鎰厪闁割偅绻冨婵堢棯閹佸仮闁哄瞼鍠栭獮鎾诲箳濠靛棗澹嬬紓鍌欒濡狙囧磻閹剧粯鐓熼幖娣€ゅ鎰箾閸欏鑰块柕鍡楀暞缁绘繈宕樿缁犳岸姊虹紒妯虹伇婵☆偄瀚弫顕€姊绘笟鈧褔鏁嶈箛娑樺窛妞ゅ繐鎳愭禍娆撴⒒閸屾艾鈧悂宕愬畡鎳婂綊宕惰濞存牠鏌曟繛褍鎳愰悞鍏肩箾鏉堝墽绉い顐㈩樀瀹曟洟鎮㈤崫銉х槇婵犵數濮撮崐褰掑闯閻戞ǜ浜滄い鎰剁稻缁€鍐磼缂佹娲寸€规洖宕灒闁告繂瀚峰鏇㈡⒒娴ｈ棄鍚归柛鐘叉捣缁辩偞绻濋崶褏鐣哄┑掳鍊曢幊蹇涘疾閺屻儱绠归悗娑欘焽缁犳牠鏌涢悩瀹犲闁?1 濠?competitorUrl闂傚倸鍊搁崐鎼佸磹閻戣姤鍊块柨鏃堟暜閸嬫挾绮☉妯诲櫧闁活厽鐟╅弻鐔封枎閳ュ磭婀撮柛鏃€鍨垮畷娲焵椤掍降浜滈柟鐑樺灥閳ь剝宕垫竟?         * 濠?supplement 闂傚倸鍊搁崐鎼佸磹閻戣姤鍊块柨鏇炲€归崕鎴犳喐閻楀牆绗掔紒鈧径灞稿亾閸忓浜鹃梺閫炲苯澧撮柛鈹惧亾濡炪倖甯婄粈渚€宕甸鍕厱闁靛鍔嬮崥顐ょ磼椤旂⒈鍎忔い鎾炽偢瀹曞爼鏁愰崨顒€顥氶梻鍌氬€搁悧濠勭矙閹烘鐤柣鎰劋閸婂灚鎱ㄥ鍡楀⒒闁绘挸銈搁弻锛勪沪鐠囨彃顬堥梺瀹狀潐閸ㄥ灝鐣烽崡鐑嗘建闁割偁鍨婚ˇ顖炴⒒閸屾瑧顦﹂柟璇х節瀵鏁撻悩鑼紱闂佺懓澧界划顖炲煕閹烘嚚褰掓晲閸涱喖鏆堥梺璇″灠閻楁捇骞冨Δ鈧～婵嬪础閻愭彃绠ｉ柣搴ゎ潐濞叉繈锝炴径濠庣劷闊洦绋戞儫闂侀潧鐗嗗ú銊╂偩妤ｅ啯鈷掑ù锝呮憸缁夌儤淇婇銉︾《缂侇喖鐗婇幏鍛村传閸曞灚顥￠梺鑽ゅ枑閻熴儳鈧凹鍠氱划缁樸偅閸愨晝鍘遍梺鏂ユ櫅閸欐劙骞嬮悩杈╁墾闂侀潧艌閺呮粓鎮￠弴銏″€甸柨婵嗛娴滄繈鎮樿箛鏂款棆闁逞屽墮閻忔艾顭垮Ο灏栧亾濮樼厧娅嶉柛鈹惧亾濡炪倖甯掗敃锔剧矓閻㈠憡鐓曢柟鍓ь棎婢规ɑ銇勯弴鐔烘噧閻撱倖銇勮箛鎾村櫝闁归攱妞藉娲閳轰胶妲ｉ梺鍛婄懃闁帮絽顕ｉ幎绛嬫晬闁绘劕顕崢鎼佹⒑閸涘﹣绶遍柛鐘宠壘鐓ら悗鐢电《閸?search-first 闂傚倸鍊搁崐鎼佸磹閻戣姤鍊块柨鏇炲€归崕鎴犳喐閻楀牆绗掔紒鈧径灞稿亾閸忓浜鹃梺閫炲苯澧撮柛鈹惧亾濡炪倖甯婄粈渚€宕甸鍕厱婵炲棗绻愰弳锝夋煏閸℃洜顦﹂摶鏍煃瑜滈崜鐔煎灳閿曞倸惟闁宠桨鑳堕ˇ銊╂⒑鐠団€崇€婚柛鏇㈡涧閹亪姊婚崒姘偓鎼佲€﹂鍕；闁告洦鍊嬪ú顏呮櫇闁逞屽墴閿濈偛鈹戠€ｎ€晠鏌曟径濠傚毐缂傚秳绀侀悾閿嬬附缁嬭銊╂煥閺冣偓閸庢娊鐛澶嬧拺闁煎鍊曞瓭濠电偠灏欐繛鈧柛鈹惧亾濡炪倖甯掗崯顖炴偟椤忓牊鐓熼煫鍥ㄦ⒐鐏忣參鏌嶇憴鍕伌闁诡喗鐟ч幑鍕惞閻熼偊妲遍梻鍌欑閹诧繝銆冮崨鏉戠柈闁秆勵殢閺佸鏌ㄥ┑鍡橆棤妞も晝鍏橀幃妤呮晲鎼存繄鏁栭梺绋匡功閸嬨倝骞冨畡閭︾叆闁告劦鈧垬鍊楃槐鎺撴綇閳轰椒妲愬Δ鐘靛仦閿曘垹鐣烽悷鎵虫婵☆垳鎳撻ˉ姘舵⒒娴ｇ顥忛柣鎾崇墦瀹曟垿宕ㄩ娑卞仺闂佸搫琚崕鏌ュ磹閸偅鍙忔俊顖滎焾閸旀艾鈹戦鐓庘偓鍧楀蓟濞戙垹鐓橀柟顖嗗倸顥?         * 濠电姷鏁告慨鐑藉极閸涘﹥鍙忛柣鎴濐潟閳ь剙鍊圭粋鎺斺偓锝庝簽閸旓箑顪冮妶鍡楀潑闁稿鎹囬弻娑㈡偄闁垮浠撮梺绯曟杹閸嬫挸顪冮妶鍡楀潑闁稿鎸剧槐鎾愁吋閸滃啳鍚Δ鐘靛仜閸燁偉鐏掗柣鐘叉穿鐏忔瑧绮ｉ悙鐑樼厽閹兼惌鍨崇粔鐢告煕鐎ｎ亜鈧悂锝炲┑瀣櫇闁逞屽墴閸╃偤骞嬮敂钘夆偓鐑芥煕濞嗗浚妯堟俊顐節濮婃椽鎮烽悧鍫熷枑濡炪値鍘奸悧鎾诲春閵忕媭鍚嬪璺猴功娴煎鏌ｉ埄鍐ㄧ瑐缂佲偓娴ｈ櫣绀婂┑鐘叉搐缁犳牠鏌嶉崫鍕櫤闁搞倖鍨堕妵鍕箣椤撶偘绨撮梺绋匡攻缁诲牆顕ｇ拠娴嬫闁靛繒濮甸ˉ婵嬫⒑缂佹ê濮囩€殿喛鍩栧鍕礋椤栨稓鍘遍柟鑹版彧缁绘繈宕ｉ崟顖涚厵妞ゆ梻鐡斿▓鏃堟煃閽樺妲搁柍璇茬Ч椤㈡顦辩紒銊ㄥ亹閳ь剙鐏氬妯尖偓姘煎墴椤㈡﹢宕楅悡搴ｇ獮婵犵數濮寸€氫即鎮伴幘缁樷拻濞达絿顭堥弳閬嶆煙绾板崬浜扮€规洘鍔橀妵鎰板箳閹惧厖绨甸梻渚€娼ч¨鈧┑鈥虫川缁?1闂傚倸鍊搁崐鎼佸磹閻戣姤鍊块柨鏃堟暜閸嬫挾绮☉妯诲櫧闁活厽鐟╅弻鐔告綇妤ｅ啯顎嶉梺绋款儍閸婃牠骞堥妸鈺佹嵍妞ゆ挾濮烽幊鈧琍/Tavily supplement 濠电姷鏁告慨鐑藉极閹间礁纾婚柣鎰▕閻掕姤绻涢崱妯诲碍閻熸瑱绠撻幃妤呮晲鎼粹剝鐏嶉梺鎼炲€曢惌鍌炲蓟閻旂⒈鏁嶉柛鈩冾殘鍟搁梺閫炲苯澧悗绗涘洤桅闁告洦鍠氶悿鈧梺鍦亾濞兼瑥鈻嶉妶鍡欑閻庢稒锚濞堢娀鏌涙繝鍐⒈闁瑰箍鍨归埥澶愬閻樻鍚呴梻浣筋嚙缁绘垵顪冮幐搴ｎ洸婵犲﹤鐗滈弫瀣煥濠靛棙鍣介柡鍡畵閺屾盯濡烽婊冨煂婵炲瓨绮撶粻鏍蓟閿濆顫呴柕蹇婃櫇閸斿憡绻涚€涙鐭嬫繝銏★耿濠€浣糕攽閻樿宸ラ柟鍐插缁傛帡鏌嗗鍡欏幗濠电偞鍨靛畷顒€鈻嶅鍥ｅ亾鐟欏嫭绀€鐎规洦鍓熼崺銉﹀緞婵炪垻鍠撻崰濠冩綇閵婃劑鍊濆铏规嫚閺屻儺鈧绱掗悩鑼х€规洘娲熼弻鍡楊吋閸涱垳鏋冮梻濠庡亜濞诧妇绮欓幒妤€纾跨€广儱顦伴悡娆撳级閸儳鐣烘俊缁㈠櫍閺?currentCandidateCount 闂傚倸鍊搁崐鎼佸磹妞嬪海鐭嗗〒姘ｅ亾妤犵偛顦甸弫鎾绘偐閸欏偊闄勭换娑㈠箣濞嗗繒浠肩紓浣插亾闁割偆鍠撶弧鈧梻鍌氱墛娓氭宕曞☉銏＄厸濞达絽鎲￠ˉ銏ゆ煛瀹€鈧崰鎾舵閹烘顫呴柣妯虹－娴滎亪鏌?         */
        if (isSearchFirstDirectDiscoverySeedMode(config)) {
            SearchRuntimePolicy runtimePolicy = resolveRuntimePolicy(config);
            int supplementCandidateLimit = searchPolicyResolver.resolveSupplementCandidateLimit(runtimePolicy, targetCount);
            int projectedCandidateCount = currentCandidateCount + Math.max(1, supplementCandidateLimit);
            return Math.max(targetCount,
                    searchPolicyResolver.resolveEffectiveTargetCountForSearchFirst(
                            config,
                            targetCount,
                            projectedCandidateCount
                    ));
        }
        return targetCount;
    }

    /**
     * direct discovery 濠电姷鏁告慨鐑藉极閹间礁纾婚柣鎰▕閻掕姤绻涢崱妯诲碍閻熸瑱绠撻幃妤呮晲鎼粹剝鐏嶉梺绋垮椤ㄥ棝濡甸崟顖氱睄闁逞屽墴瀹曟洟骞庨挊澶婄€┑鐘绘涧椤戝棝鎮￠悢闀愮箚妞ゆ牗绻冮鐘绘煥濞戞效闁哄苯绉剁槐鎺懳熷ú缁橆棃闂備礁鎼懟顖滅矓閻戦摪銊︾瑹閳ь剟寮诲☉銏犵閻庢稒顭囧▓銈囩磽?stable locator 闂傚倸鍊搁崐鎼佸磹妞嬪海鐭嗗ù锝夋交閼板潡姊洪鈧粔鏌ュ焵椤掆偓閸婂湱绮嬮幒鏂哄亾閿濆簼绨介柡灞熷啠鏀介柣鎰綑閻忕喖鏌涢妸銉хШ鐎?docs/open/developer/help 缂傚倸鍊搁崐鎼佸磹閹间礁纾归柣鎴ｅГ閸婂潡鏌ㄩ弬鍨挃闁活厽鐟╅弻鐔封枎闄囬褍煤椤擃潿鈧礁鈽夐姀鈥斥偓鐑芥煕椤愶絿绠栭柛鐘成戦〃銉╂倷閺夋垵顫嶉梺璇″灡濡啴寮幇鏉跨＜婵炴垶鐭花閿嬬節绾板纾块柛瀣灴瀹曟劙寮撮悩鍐插簥闂佽澹嗘晶妤呭磻鐎ｎ喚鍙撻柛銉ｅ妼閹肩霉濠婂棭娼愰柕鍥у瀵粙鈥﹂幋婵囶吘闁荤喐绮庢晶妤冩暜濡ゅ懎纾婚柟鎵閻撴瑧绱撴担濮戭亞绮顓狀洸婵炴垯鍨洪埛鎺戙€掑锝呬壕闂侀€炲苯澧伴柛瀣洴閹崇喖顢涘☉娆愮彿婵炲鍘ч悺銊╂偂閺囩喍绻嗘い鏍ㄧ矊瀛濆┑鐐茬湴閸婃繈骞嗙仦杞挎棃宕ㄩ鎯у箞闂備焦鏋奸弲娑㈠疮娴兼潙鐓″鑸靛姈閻撶喐銇勯幘璺烘珡婵炲牊绮撻弻锛勪沪鐠囨彃顬堥梺瀹狀潐閸ㄥ灝鐣烽崡鐐嶆梹鎷呴崷顓熸緬闂?     * 闂傚倸鍊搁崐椋庣矆娓氣偓楠炴牠顢曚綅閸ヮ剦鏁冮柨鏇楀亾闁汇倗鍋撶换婵囩節閸屾粌顤€闂佺顑戠换婵嬪蓟閺囥垹閱囨繝闈涙川閳规稒绻濆▓鍨灕婵炲懏娲滈幑銏犫槈濮楀棗鏅犲銈嗘瀹曠敻鎯勬惔锝囩＝濞达絾褰冩禍楣冩⒑缁嬭法鐏遍柛瀣仱閹ょ疀閹垮啰鍞甸柣鐘荤細濞咃絾鏅堕弴銏＄厱闁哄啠鍋撴慨妯稿妿濡叉劙骞橀幇浣告倯闂佺硶鍓濋敋缁剧偓濞婂娲传閸曨厾鍔圭紓浣虹帛缁诲倿鎮鹃柨瀣檮闁告挸寮堕弲婵嬫⒑闂堟稓绠冲┑顕呭弮楠炲繘濡舵径瀣ф嫽闂佺鏈懝楣冨焵椤掍胶鎽冨褎绻堝娲传閸曨剙娅ф繝娈垮枟閹告娊骞冩ィ鍐╁€婚柦妯猴級閵娧勫枑閻忕偠袙閺岋附銇勮箛鎾跺闁绘挾鍠栭弻銊モ攽閸℃ê娅ゅ┑鐘亾濞寸厧鐡ㄩ悡鏇㈡煙閻愵剚缍戦柣蹇ュ缁辨帞绱掑Ο鍏煎垱濡ょ姷鍋涢澶愬箖濞嗘挸绾ч柟瀵稿У椤撳姊婚崒娆掑厡妞ゎ厼鐗撻、鏍幢濞戞顔夐梺鎼炲労閸撴瑩鎷戦悢鍏肩厽闁哄倸鐏濋幃鎴︽煕鐎ｃ劌濮傞柡灞剧洴閳ワ箓骞嬪┑鍛泿婵犵數鍋涢悧鍡涙儗閸岀偛绠栫€瑰嫰鍋婇悡銉╂煕閺囥劌澧伴柛鎴節閹鈻撻崹顔界亪婵犫拃鍐╂崳闁告帗甯掗～婵嬫嚋閻㈤潧濮搁柣搴＄畭閸庨亶骞婇幇顓烆嚤閻庯綆鍠楅埛鎴︽煕濠靛棗顏╅柍褜鍓欓悥鐓庣暦閺囩喐缍囬柕濞у本閿ら梻浣虹帛閸旓箓宕滃☉姘变笉濡わ絽鍟悡鏇㈡煃閳轰礁褰侀柟瀵稿С閻掑﹪鏌曟繛鐐珕闁绘挻娲熼幃妤呮晲鎼粹€茬凹闁诲繐绻戞竟鍡涘焵椤掆偓閻忔艾顭垮Ο灏栧亾濮樼厧澧撮柛鈹垮劜瀵板嫭绻濇惔銏犲厞濠碘剝褰冮張顒勬偋濡も偓閺嗏晜绻濋悽闈浶ラ柡浣规倐瀹曟垿鎮欓崫鍕€梺鍓插亝濞叉牜绮绘导瀛樼厱婵犻潧瀚崝銈夋煟椤撶喐宕岄柡宀嬬秮楠炲鏁愰崱鈺傤棄缂傚倷璁查崑鎾绘煕鐏炲墽鈯曢柛娆忕箲娣囧﹪鎮欐０婵嗘婵炲瓨绮撶粻鏍箖濡ゅ啯鍠嗛柛鏇ㄥ墰閳规稓绱撻崒姘毙＄紒鑸靛哺婵″瓨绗熼埀顒€顕ｉ鈧畷鐓庘攽鐎ｎ亝鏆梻鍌欑窔濞佳呮崲閹烘挻鍙忛柣鎴ｅГ閸嬪倿鏌熸潏楣冩闁抽攱鍨块弻銈嗘叏閹邦兘鍋撻弴鐐垫懃濠电姷鏁搁崑鐘活敋濠婂懐绀婂〒姘ｅ亾妤犵偛鍟撮幃娆撴倻濡粯鐝曢梻浣虹帛閸旓箓宕滃顑芥灁濞寸厧鐡ㄩ埛鎴︽偡濞嗗繐顏╅柛鏂诲€濋弻锝嗗箠闁告梹鍨甸悾鐑藉即閵忊€充簻闂佸吋绁撮弲娑溿亹閸曨垱鈷戦柟鑲╁仜閸旀﹢鏌涢弬璺ㄐｇ€垫澘瀚板畷鐔碱敍濞戞艾骞堥梺鐟板悑閻ｎ亪宕硅ぐ鎺撳€堕柕澶嗘櫆閻撳啴鏌﹀Ο渚▓婵☆垪鍋撴俊銈囧Х閸嬬偤鏁冮姀銈冣偓浣糕槈濮楀棙鍍靛銈嗗姂閸╁嫬螞濠婂牊鈷掗柛灞捐壘閳ь剟顥撶划鍫熺瑹閳ь剟鐛径鎰櫢闁绘ê鍟垮▓鐐烘⒑閸涘﹥瀵欓柛娑卞灣閸橆垶姊绘担鍛婂暈婵炶绠撳畷銏＄鐎ｅ墎绋忛梺鍝勬储閸ㄦ椽鍩涢幋鐐簻闁瑰搫妫楁禍鍓х磽娴ｅ壊妲告い鏇ㄥ弮楠炲骞橀鑺ユ珖闂佺鏈粙鎾诲矗閸℃稒鈷戦柛婵嗗婢跺嫭銇勯妸銉﹀櫤缂佸倸绉甸妶锝夊礃閳圭偓瀚奸梻浣告啞閹逛胶浜稿▎鎾冲偍婵炴垯鍨洪悡鏇㈡煏婵炲灝鍔ら柨娑樼Ч閺屾盯骞掗幘铏癁濡炪們鍨洪敃銏℃叏閳ь剟鏌嶉崫鍕偓鐟扳枍閸儲鈷戦柤濮愬€曞皬闁荤姭鍋撻柨鏇炲€归崑鍌炴煕椤愩倕鏋庢い鏇憾閺屾盯濡烽敐鍛瀷缂備胶濞€缁犳牠寮婚悢琛″亾閻㈡鐒惧ù鐙呯畵閺岀喖顢涘顒変患闂傚洤顦扮换婵囩節閸屾凹浼€缂備胶濮靛畝鎼佸蓟濞戙垺鍋嗗ù锝呮憸娴犵鈹戦纭锋敾婵＄偠妫勯悾鐑筋敃閿曗偓缁€瀣煕椤垵鈧綁鏁冮埀顒勫煘閹达附鍊烽柣鎰帨閸嬫挾鈧綆鍓氬畷鍙夌節闂堟稒鐭楃紒璇叉閹鈽夊▍顓т簽濞嗐垽鎮欓悜妯衡偓鐢告煥濠靛棝顎楅柛妯绘尦閺岋紕鈧綆鍋嗘晶鐢告煙椤旂瓔娈滈柣娑卞櫍瀹曞綊顢欓悡搴經闂傚倷鑳堕幊鎾诲疮閸啔褰掓倻閽樺顔嗛柣搴秵閸犳牠鎷戦悢鍏肩厪濠㈣泛鐗嗛崝銈呂旈悩鍙夊枠婵﹥妞藉畷婊堝箵閹哄秶鎸夐梻浣规偠閸斿繘锝炴径宀€鐭夌€广儱顦伴崐閿嬨亜閹达絾纭剁紒鐘冲哺濮婅櫣绱掑Ο鍝勵潔閻熸粍婢橀崯鎾春閳ь剚銇勯幒鍡椾壕濠电姭鍋撻梺顒€绉撮悞鍨亜閹烘垵鈧綊寮抽埡鍛厱婵☆垳濮村ù鍕礊閺嶃劎绡€闂傚牊渚楅崕蹇曠磼閻樺磭鈽夐棁澶愭煥濠靛棙鍣洪柛鐔哄仦閵囧嫰寮崫鍕闂?public search闂?     * 闂傚倸鍊搁崐鎼佸磹妞嬪海鐭嗗〒姘ｅ亾妤犵偛顦甸弫鎾绘偐閸愯弓鐢婚梻渚€娼ч敍蹇涘川椤栨粌甯梻鍌欒兌缁垶宕归悡骞盯宕熼娑樷偓鍧楁煕椤垵浜栧ù婊勭矒閺岀喖宕崟顒夋婵炲瓨绮撶粻鏍ь潖濞差亜浼犻柛鏇ㄥ幖閳峰牓姊洪崨濠冨鞍闁烩晩鍨伴锝夘敃閿旂粯鏅╁┑鐐存綑鐎涒晛顪冩禒瀣ㄢ偓渚€寮崼婵嗙獩濡炪倖鎸鹃崑娑⑺夌€ｎ喗鈷掗柛灞剧懅椤︼箓鏌熺喊鍗炰簽闁归濞€閹瑩宕崟顓фЧ婵＄偑鍊栭崝妤呭窗鎼淬垻顩插Δ锝呭暞閳锋垶銇勯幒鍡椾壕缂備礁顦遍弫濠氬箖閳ユ枼鏋庣€电増绻傜紞濠囧极閹版澘宸濇い鎾跺枔娴滅偓绻濋悽闈涗粶闁告艾顑夊畷婵嬪冀椤撶倣锕傛煕閺囥劌鐏犵紒鐘崇⊕閵囧嫰骞嬮悙鎻掑Б婵炲濯崳锝咁潖濞差亜浼犻柛鏇ㄥ枛椤忣參姊洪崗鍏笺仧闁搞劌鐖奸獮鍐箚瑜夐弨浠嬫倵閿濆簼绨介柛鏃撶畱椤啴濡堕崱妤€娼戦梺绋款儐閹瑰洭寮婚妸鈺佸嵆婵☆垵娅ｆ导鍫ユ⒑鏉炴壆顦︾紒澶屾嚀閻ｇ兘鎮㈤悡骞晠鏌曟径鍫濃偓鏇⑺夊鑸碘拻濞撴埃鍋撴繛鑹板吹閳ь剟娼ч惌鍌氱暦閵忋倕绀傞柤娴嬫櫓濞村嫬鈹戦悩璇у伐闁瑰啿绻樺畷浼村箛閻楀牏鍘藉┑掳鍊愰崑鎾绘煟濡も偓缁绘ê鐣烽敐澶婄劦妞ゆ帒瀚埛鎴︽偣閸ワ絺鍋撻搹顐や簽缂傚倷绶￠崰妤呮偡閿旂晫鈹嶅┑鐘插亞濞兼壆鈧厜鍋撳┑鐘插敪椤忓牊鈷?/docs 闂?open 婵犵數濮撮惀澶愬级鎼存挸浜炬俊銈勭劍閸欏繘鏌ｉ幋锝嗩棄缁惧墽绮换娑㈠箣濞嗗繒浠奸梺姹囧€ら崳锝夊蓟閵堝绠涘ù锝呮憸娴犳粍绻涚€涙鐭婄紓宥咃躬瀵鎮㈤搹鍦紲闂侀潧绻掓慨鐢告倶閸垻纾藉ù锝嗗絻娴滈箖姊虹粙璺ㄧ伇闁稿鍋ら幃陇绠涢幙鍐數闁荤娀缂氬▍锝嗘櫠閺囥垺鐓曢柡鍐ｅ亾婵ǜ鍔庡Σ鎰板箻閹颁礁鎮戦梺绯曞墲閿氱痪鐐▕濮?     */
    private int resolveVerificationCandidateLimit(CollectorNodeConfig config,
                                                  List<SourceCandidate> candidates,
                                                  int targetCount,
                                                  int minVerifiedCount) {
        int candidateCount = candidates == null ? 0 : candidates.size();
        int defaultLimit = Math.max(minVerifiedCount, Math.min(targetCount, candidateCount));
        if (!isDirectDiscoveryCandidatePool(config, candidates)) {
            return defaultLimit;
        }
        return Math.min(candidateCount, Math.max(defaultLimit, Math.min(candidateCount, 8)));
    }

    private boolean isDirectDiscoveryCandidatePool(CollectorNodeConfig config, List<SourceCandidate> candidates) {
        if (config == null || candidates == null || candidates.isEmpty()) {
            return false;
        }
        if (config.getSourceCandidates() != null && !config.getSourceCandidates().isEmpty()) {
            return false;
        }
        if (config.getCompetitorUrls() == null || config.getCompetitorUrls().isEmpty()) {
            return false;
        }
        return candidates.stream().anyMatch(candidate -> {
            String discoveryMethod = candidate == null ? null : candidate.getDiscoveryMethod();
            return "DIRECT_LOCATOR".equalsIgnoreCase(discoveryMethod)
                    || "FAMILY_TEMPLATE".equalsIgnoreCase(discoveryMethod)
                    || "FAMILY_SUBDOMAIN_TEMPLATE".equalsIgnoreCase(discoveryMethod);
        });
    }

    /**
     * public search 闂傚倸鍊搁崐鎼佸磹妞嬪海鐭嗗〒姘ｅ亾妤犵偛顦甸弫宥夊礋椤撶姷鍘梻浣告贡閸庛倝銆冮崨鏉戝瀭闁稿本绋撶弧鈧梻鍌氱墛娓氭宕曢幇鐗堢厱闁靛牆鎳愭晶锕傛煛瀹€鈧崰鎰焽韫囨稑绀堢憸蹇涘汲閻樼粯鈷戦柛娑橈工婵偓闂佺顑嗛幑鍥ь潖缂佹ɑ濯撮柦妯侯槸閹偤姊洪崫銉バｉ柛鏃€鐗滅划鈺呮偄閻撳骸鑰垮┑鐐村灦閿氶柛瀣Ч濮婂搫效閸パ呬患闂佺顕滅换婵嬪箖閳ユ枼鏋庨柟鐐綑閳ь剙鐏氱换娑㈠箣閻愬灚鍣介梺缁樺笩濡嫰鈥﹂懗顖ｆ闂佸憡鎸婚惄顖炲箚閳ь剚銇勮箛鎾跺⒈闁轰礁娲弻锝夊箛椤撶姰鍋為梺鍦焾閸熸潙顫忓ú顏呯劵闁绘劘灏€氭澘顭胯瀵爼濡甸崟顖ｆ晝闁靛繆鏅滈埢鍫ユ⒑閻愯棄鍔电紒鐘虫尭閻ｇ兘宕奸弴鐐靛幐闂佸憡鍔︽禍璺何ｇ粙搴撴斀闁绘﹩鍠栭悘杈ㄧ箾婢跺娲存い銏＄墵瀹曟﹢鍩￠崘鐐敜婵犵數濮撮敃銈夋偋婵犲洦鍋傞柡鍥╁枂娴滄粍銇勯幘璺烘瀻闁诲繈鍎遍湁婵犲﹤瀚粻鏍煏閸パ冾伂闁绘柧绶氶弻娑㈠Ψ閿濆懎顬嬮梺鍛婎焽閺佽顫忔繝姘＜婵ê宕·鈧紓鍌欑椤戝棝骞愭繝姘柧闁靛濡囩弧鈧梺姹囧灲濞佳勭閿曞倹鐓欑紒瀣閸熺偤鏌￠崨鏉跨厫闁诡垱妫冩俊鎼佸Ψ瑜忛弸鈧梻鍌欒兌缁垶銆冮崨瀛樺亱濠电姴鍟崹鏃堟煛婢跺鐒炬繛纭风磿閹插憡鎯旈妸銉ь唶婵＄偛顑呯€涒晠顢曢懞銉﹀弿婵妫楁晶濠氭煕閵堝棙绀嬮柡灞剧洴椤㈡洟鏁愰崱娆樻К缂傚倷鑳舵慨鎶藉础閹剁晫宓侀柡宥庡亜閸ㄦ繈鎮峰▎蹇擃伌闁逞屽墮濡繈寮婚悢铏圭煓闁圭楠稿▓妤呮⒑閸濆嫮鐒跨紓宥佸亾缂備胶濮甸惄顖氼嚕閹绢喗鍊烽柣妤€鐗嗘刊鏉库攽閻樺灚鏆╅柛瀣仩閵囨劙宕橀鍛簥濠殿喗銇涢崑鎾垛偓瑙勬礃濡炰粙宕洪埀?     * 濠电姷鏁告慨鐑藉极閸涘﹥鍙忛柣鎴濐潟閳ь剙鍊圭粋鎺斺偓锝庝簽閸旓箑顪冮妶鍡楀潑闁稿鎹囬弻娑㈡偄闁垮浠撮梺绯曟杹閸嬫挸顪冮妶鍡楀潑闁稿鎸剧槐鎾愁吋閸滃啳鍚Δ鐘靛仜閸燁偉鐏掗柣鐘叉穿鐏忔瑧绮ｉ悙鐑樷拺鐟滅増甯掓禍浼存煕閹惧娲存い銏＄懇楠炲洭顢栭懞銉︽澑闂備胶绮…鍫ヮ敋濠婂喚鍟呴柕澶嗘櫆閻撴瑩鏌涢幇顓犲弨闁告瑥瀚埀顒冾潐濞叉﹢宕归悽鍓叉晣濠靛倻顭堥悙濠囨煃閸濆嫬鏋︾紒鍗炲暱閳规垿鎮欓懜闈涙锭缂傚倸绉崑鎾愁渻閵堝骸浜滄い锕侀哺缁傚秹宕ㄦ繝鍌ゅ殼闁诲孩绋掗…鍥储娴犲顥婃い鎰╁灪婢跺嫰鏌熺亸鏍ㄦ珔閾伙綁鏌嶈閸撶喖骞冨Δ鈧埢鎾诲垂椤旂晫浜鹃梻浣芥〃缁€渚€鈥﹂悜钘壩ュù锝堝€介弮鍫濆窛妞ゆ挾濯Σ鐗堜繆閻愵亜鈧牕顫忚ぐ鎺戠？閻庡湱濮版禍鐟般€掑锝呬壕闂佸搫鐭夌徊楣冨箚閺冨牆围閹兼番鍨荤粔鐑芥⒒娴ｅ搫鍔﹂柛鎾寸箓鐓ゆ繝濠傚椤╄尙绱掔€ｎ亞姘ㄩ柡瀣叄閺岀喓鈧稒顭囩粻鎾绘煟韫囨搫韬慨濠呮閹瑰嫰濡搁妷锔句簴濠电姵顔栭崰妤呭箰閸愯尙鏆?DOCS/PRICING 婵犵數濮烽弫鍛婃叏閻戣棄鏋侀柟闂寸绾惧潡鏌熺€电啸缂佲偓婵犲伅褰掓晲閸涱喛纭€濡炪倐鏅滈悡锟犲蓟濞戙埄鏁冮柨婵嗘椤︺儳绱撴担鍝勑㈤柟纰卞亰閸╃偤骞嬮敃鈧壕鍏兼叏濮楀棗骞栭柡鍡楃墦濮婃椽宕崟顒€顎涢梺鍛婃尰閻╊垶宕洪埀顒併亜閹哄棗浜鹃梺鍛娚戠划鎾崇暦閹达箑绠荤紓浣诡焽閸樻捇鎮峰鍕煉鐎规洘绮撻幃銏ゆ嚃閳轰胶銈﹂梺璇插嚱缂嶅棝宕戦幒鎳筹綀銇愰幒鎴狀啇闁哄鐗嗘晶浠嬪箖婵傚憡鐓ユ繛鎴炵懅閻﹥銇勯鍕殻濠碘€崇埣瀹曞崬螣閻戞ɑ顔傞梻鍌欑閹芥粍鎱ㄩ幘顔界厐闁挎繂顦弰銉︾箾閹存瑥鐏╅柛鎰ㄥ亾婵＄偑鍊栭幐楣冨窗閹捐鍌ㄩ柟闂寸劍閸婂灚顨ラ悙鑼虎闁告梹纰嶇换娑氭嫚瑜忛悾鐢碘偓瑙勬礈閺佸宕洪埀顒併亜閹烘垵顏柍閿嬪灩缁辨挻鎷呴懖鈩冨灩娴滃憡瀵肩€涙鍘介梺缁樻⒐濞兼瑩宕濋妷鈺傜厽闊洦鏌ㄥù顔芥叏婵犲啯銇濈€殿噮鍓熸俊鐑芥晜閻ｅ苯绲介梻鍌欑閹芥粓宕抽妷鈺佸瀭闁告劖绁撮弸搴ㄦ煏韫囧鈧洜绮婚悽鍛婄厵閻熸瑥瀚峰▓鏃傜磽瀹ュ拑宸ラ柣锝呭槻铻栭柛娑卞枓閹疯崵绱撻崒姘卞ⅱ濠殿喗鎸抽、妤呭閳ヨ尙绠氶梺缁樺姦娴滄粓鍩€椤掍胶澧电€殿喖顭烽弫鎾绘偐閼碱剨绱?open/developer 缂傚倸鍊搁崐鎼佸磹閹间礁纾归柣鎴ｅГ閸婂潡鏌ㄩ弬鍨挃闁活厽鐟╅弻鐔封枎闄囬褍煤閿曗偓椤洩绠涘☉妯煎幋闂佽鍨庨崒姘兼婵犵數鍋犻幓顏嗗緤娴犲鐤い鏍€涙径瀣窞闁归偊鍙庡鎸庣節閵忥絽鐓愰柛鏃€鐗犲畷鎰版偨閸涘﹦鍙嗗┑鐘绘涧濡盯宕欓崷顓犵＜闁靛鍔岄崥褰掓煃瑜滈崜姘额敊閺嶎厼绐楁俊銈勮兌缁犳儳鈹戦悩鍙夋悙闁藉啰鍠愮换娑㈠箣閻愬啯宀稿鍛婃償閵忋垻顔曢梺绯曞墲閿氶柣蹇婃櫊閹锋垿宕￠悙鈺傛杸闂佺粯蓱閸撴岸宕箛娑欑厱闁绘ê纾。鏌ユ煙娓氬灝濮傛俊顐㈠暙閳藉娼忛埡浣感ㄦ繝鐢靛Х閺佸憡鎱ㄩ悽鍛婂殞濡わ絽鍟崐宄扳攽閻樺弶澶勯柣鎾冲暣閺屾稑鈹戦崱妤婁患闂侀€炲苯澧柟顔煎€块獮鍐倷閸濆嫮顔愭繛杈剧到濠€閬嶅储娴犲鐓欓柤娴嬫櫈钘熼梺鍛婃尰缁诲嫰骞忚ぐ鎺撴櫢闁绘ê纾崢閬嶆煟鎼搭垳绉甸柛瀣鐓ら悗娑欙供濞堜粙鏌ｉ幇顖ｅ殝闂婎剦鍓熼弻娑㈠煛娴ｅ壊浼冮悗瑙勬礃閿曘垽銆侀弮鈧幏鍛村捶椤撴繄鑳哄┑鐘殿暜缁辨洟宕戦幋锕€纾归柡宥庡幖缁犲綊鏌嶆潪鎵窗闁绘帊绮欓弻娑滎槼妞ゃ劌鎳橀幃鐐哄垂椤愮姳绨婚梺鐟版惈濡绂嶉幆褜娓婚柕鍫濈箳閻ｉ亶鏌ｈ箛鏃傤暡缂?     */
    private boolean shouldExpandSearchCandidateThroughDirectDiscovery(CollectorNodeConfig config,
                                                                      SourceCandidate candidate) {
        if (!isTrustedSearchExpansionRoot(config, candidate)) {
            return false;
        }
        SourceCandidate normalizedCandidate = normalizeCandidateCanonicalUrl(candidate);
        if (normalizedCandidate == null || !StringUtils.hasText(normalizedCandidate.getUrl())) {
            return false;
        }
        String rootUrl = toRootUrl(normalizedCandidate.getUrl());
        if (!StringUtils.hasText(rootUrl)) {
            return false;
        }
        boolean rootHit = normalizedCandidate.getUrl().equals(rootUrl);
        boolean officialHit = "OFFICIAL".equalsIgnoreCase(normalizedCandidate.getSourceType());
        return rootHit || officialHit;
    }

    /**
     * 闂傚倸鍊峰ù鍥х暦閻㈢绐楅柟閭﹀枛閸ㄦ繈骞栧ǎ顒€鐏繛鍛У娣囧﹪濡堕崨顔兼缂備胶濮抽崡鎶藉蓟閵堝棙鍙忛柟閭﹀厴閸嬫挸螖閸涱喖鍓归梺鍓插亖閸庢煡鎮￠悩娴嬫斀妞ゆ棁妫勬慨鍥煃瑜滈崜姘舵偋閻樿尙鏆﹀ù鍏兼綑楠炪垺绻涢幋鐐垫噮闁告ê宕—鍐Χ閸℃衼缂備浇灏▔鏇犲垝婵犳艾鍐€妞ゆ挾鍠撻崢钘夆攽閻樼粯娑ч悗姘煎墴閸┾偓妞ゆ帊鑳剁粻鐐搭殽閻愭彃鏆熼柟鐟板閹即鍩勯崘鐐秾闂傚倷绀侀幖顐ゆ嫚閻愬搫绀冩い顓熷灦濞撴劗绱撻崒姘偓鎼佸磹妞嬪孩顐介柨鐔哄У閸嬪倿鏌涢鐘插姎缂佹劖顨嗛幈銊ノ熼幐搴ｃ€愮紓浣哄У閻楁濡甸崟顖氬嵆婵°倐鍋撳ù婊勫劤椤啴濡堕崘銊т痪濠电偠灏欓崰鏍ь嚕椤愶箑绠涢柡澶婄仢閼板潡妫呴銏″婵﹦绮粋宥呪枎閹剧补鎷洪柣鐘叉处瑜板啴顢楅姀掳浜滈柡鍐ｅ亾闁绘濮撮悾閿嬪閺夋垵鍞ㄥ銈嗗姧缁茶姤鎯旀繝鍥ㄢ拺闁革富鍘奸。鍏肩節閵忊槄鑰块柟顖氳嫰铻栭柛鎰ㄦ櫅閺嬫垿姊洪崫鍕偓褰掝敄濞嗗浚鐒介柡宓偓閺€浠嬫煟濡搫鏆遍柛銈庡墴閺屸剝鎷呴崨濠傛灎闂佸搫鐭夌紞浣割嚕椤掑嫬鍨傛い鏇炴噺椤ユ垶绻濈喊妯峰亾閾忣偀鏋欓梺鎼炲姀濞夋盯锝炶箛鎾佹椽顢旈崟顓у敹闂佺澹堥幓顏嗗緤閸濆嫀锝夊醇閵夛腹鎷洪梺缁樺灍閺呮稒鏅堕懠顒傜＜婵＄偟绮▍娣?provider 闂傚倸鍊搁崐鎼佸磹妞嬪海鐭嗗〒姘ｅ亾妤犵偞鐗犻、鏇㈠Χ閸屾矮澹曞┑顔矫畷顒勫储鐎电硶鍋撶憴鍕缂傚秴锕獮鍐╃鐎ｎ亜鐎銈嗗姂閸ㄥ湱鑺遍妷鈺傜厽閹艰揪绱曢悾顓㈡煕鎼粹€宠埞閻撱倝鏌ㄩ弴鐐测偓鍝ュ缂佹ɑ鍙忔俊鐐额嚙娴滈箖姊虹拠鈥虫灍闁挎洏鍨介獮鍐ㄢ枎閹炬惌妫冨┑鐐村灦椤ㄥ懘藟濮樿埖鈷掑ù锝堟閵嗗﹪鏌￠崨顔炬创鐎规洘鍔欏畷绋课旈埀顒勬嫅閻斿摜绠鹃柟瀛樼懃閻忣亪鏌涙繝鍌ょ吋闁哄本鐩崺鍕礃閻愵剛鏆┑鐐差嚟婵秹宕ㄩ婊愮闯濠电偠鎻紞鈧い顐㈩樀閹繝濡烽敂杞扮盎闂佸搫娲㈤崝灞筋嚕椤旂瓔娈介柣鎰絻閺嗙偤鎮楅棃娑栧仮鐎殿喖鐖奸獮濠囨倷閽樺顫囬梺鍝勮閸旀垵顕ｉ鍕瀭妞ゆ棁顫夌€垫牠姊绘担铏广€婇柡鍛〒缁棃鎮烽柇锔界稁濠电偛妯婃禍婊勫閻樼粯鐓曢柡鍥╁仧娴犳盯鎳栭弽顓熲拻濞达絼璀﹂悞鐐叏濮楀牆顩ù婊咁焾閳诲酣骞橀弶鎴炵枀闂傚倷绶￠崜娆戠矙娓氣偓瀵偆鈧綆鍠楅悡鍐喐濠婂牆绀堟慨姗嗗幘閳?     * 闂傚倸鍊搁崐椋庣矆娓氣偓楠炴牠顢曚綅閸ヮ剦鏁冮柨鏇楀亾闁汇倗鍋撶换婵囩節閸屾粌顤€闂佺顑戠槐鏇㈠箟濮濆瞼鐤€婵炴垶顭囬悾鍝勵渻閵堝棙纾甸柛瀣尰閵囧嫰濮€閳╁啰顦伴梺杞扮閸熸挳宕洪埀顒併亜閹烘垵鈧悂藟濮樿埖鍋℃繛鍡楃箰椤忣偆绱掗悩鑽ょ暫闁哄本鐩崺鍕礂閳哄倸鐏╁ù婊勬倐婵℃悂鍩￠崒婊冨箞闂佽鍑界紞鍡涘磻閸涱垯鐒婇柟娈垮枓閸嬫捇宕归锝囧嚒闁诲孩鍑归崢楣冨箲閵忕姭鏀介悗锝庝簽閿涙粌鈹戦悩娆忓暟娴犮垹霉閻樿尙绠茬紒缁樼箞閹粙妫冨☉妤€鎽嬮梻浣筋潐濡炴寧绂嶉悙鍨潟闁绘劕鎼悞鍨亜閹哄秷鍏岀紒鐘冲劤椤法鎹勬笟顖氬壉缂備讲鍋撻柛顐犲劜閻撴洟鏌熼悜妯诲碍婵炴惌鍠栭埞鎴﹀焺閸愨晛鈧劙鏌涢埞鎯у⒉闁瑰嘲鎳愮划娆撳箰鎼搭喖鎮嬪┑鐘垫暩婵兘寮崨濠冨弿闁圭虎鍠楅弲婵嬫煏閸繍妲归柛瀣儔閺屾盯寮撮妸銉т哗闂佺粯甯掗悘姘跺Φ閸曨垰绠抽柛鈩冦仦婢规洘淇婇悙顏勨偓褏绱撳璺虹闁规儼妫勮繚闂佸憡鍔﹂崰鏍ф暜闂備線娼ч敍蹇涘礋椤掑倹娈鹃梻鍌氬€风粈渚€骞夐敓鐘茬闁挎梻鏅々鍙夌節闂堟侗鍎滅紓宥嗙墱閳ь剙绠嶉崕閬嵥囨导鏉戠厱闁硅揪闄勯悡鏇熺節闂堟稒顥滄い蹇ｄ簼閵囧嫰濡烽敂鍓х厜闂佸搫鏈粙鎺旀崲濠靛绀嬫い鎺嗗亾婵炲牆鑻—鍐Χ鎼存繄鐩庨梺鍝ュ枑濞兼瑩鎮鹃悜钘夌婵°倓绀侀埀顒傚厴閺屻倗鍠婇崡鐐测拻闂佸摜鍋為幐鍐差潖缂佹ɑ濯寸紒娑橆儐缂嶅牓鎮楃憴鍕妞ゎ厼娲︾粋宥囩矙鎼存挻鐎婚梺鍦亾濞兼瑦绂掗鐐╂斀闁绘绮☉褎銇勯幋婵囶棦闁诡噯绻濆鎾閿涘嫬骞嶉梻浣虹帛閸ㄦ儼鎽梺缁樻尪閸庢煡濡甸崟顖涙櫜闁告侗鍘介崐顖炴⒑闂堟稒鎼愰悗姘嵆閻涱噣骞掑Δ鈧粻濠氭煕閹捐尪鍏岄柛妯荤矒閺岋絾鎯旈姀鈺佹櫛闂侀潻缍嗛崳锝呯暦濠婂牊鍋勫瀣濞堥箖姊洪悡搴㈠暈妞ゆ梹鐗曞嵄鐟滅増甯掔粻褰掑级閸繂鈷旂紒澶婄仛娣囧﹪骞撻幒鎴炐╅梺瀹狀潐閸ㄥ潡骞冨▎鎾崇骇闁瑰濮冲鎾寸節濞堝灝鏋涢柨鏇樺€濋垾锕€鐣￠幍顔芥闂佺粯顨呴悧濠囧磿閻斿吋鐓ユ繝闈涙婢ф洜浜歌箛娑欌拻濞达綀妫勬禍褰掓煃瀹勬壆澧︾€规洘绮岄～婵嬪箥娴ｉ晲澹曞┑顔斤供閸樺ジ宕ú顏呯厸閻忕偟鍋撶粈鍐偓鍨緲鐎氭澘鐣烽悡搴樻斀闁告劑鍔嬫竟鏇炩攽鎺抽崐鏇㈠疮閻楀牏鈻旂€广儱鎮胯ぐ鎺撳亹鐎瑰壊鍠栭崜閬嶆⒑缂佹ɑ灏甸柛鐘崇墵瀵濡搁妷銏℃杸闂佺硶鍓濋敃鈺佄涢妶澶嬧拺闁荤喐婢樼敮鐘电磼閼搁潧鍝烘鐐插暢閵囨劙骞掗幋鐘垫澑婵＄偑鍊栭弻銊╁触鐎ｎ喗鍊堕柛顐犲劜閳锋垿鏌熼懖鈺佷粶濠碘€茬矙閺屾稒鎯旈姀鐘灆闂佺偨鍎荤粻鎾翠繆閹间礁鐓涘ù锝勮濡蹭即姊绘笟鈧褔鈥﹂鍕亗闁割偁鍎遍懜鍦喐閻楀牆绗氶柍閿嬪浮閺屾稓浠﹂崜褎鍣梺鍛婃煥缁夊爼骞夐幖浣哥骇闁割煈鍠楀▓顓㈡煟閹惧崬鈧牠濡甸崟顖氱閻犻缚妗ㄥЧ妤呮⒑閸濆嫷鍎愰柣妤冨█瀵濡搁埡浣稿祮闂佺粯鍔栫粊鎾磻閹捐浼犻柕澹懐鍔堕梻浣侯焾閺堫剛绮欓幘璺哄К闁逞屽墯缁绘繈鎮介棃娴躲垽鏌涙繝鍐╁€愮€殿喗濞婇幃銏ゆ閻愭煡鍙勭€规洖鐖兼俊鎼佹晜鏉炴媽妾搁梻鍌欑閹碱偊鎳熼婊呯煋閻熸瑥瀚换鍡涙煟閹达絽袚闁哄懏绮撻弻娑㈠箻濡も偓閼活垶寮崷顓犵＝?     * 闂傚倸鍊搁崐鎼佸磹妞嬪海鐭嗗〒姘ｅ亾妤犵偛顦甸弫鎾绘偐閸愯弓绨绘俊鐐€栫敮鎺楀磹缂佹鈻旂€广儱顦伴悡娆撳级閸繂鈷旈柣锝変憾閺屾盯濡搁妷銉㈠亾瑜版帒桅闁告洦鍨伴崘鈧梺闈浤涢埀顒€危閸繍娓婚柕鍫濇缁€澶婎渻鐎涙ɑ鍊愭鐐茬墦婵℃悂濡烽钘夌紦闂備胶纭堕崜婵嬨€冮崨杈剧稏闁宠桨璁查弨鑺ャ亜閺囩偞顥犻柛鎺斿閵囧嫰鏁傞悙顒佹瘓閻庢鍠栭…鐑藉极閹版澘妞藉ù锝呮惈瀵娊姊绘担鍛婂暈婵炶绠撳畷鎴﹀箳閹宠櫕绋掗幆鏃堝Ω閿旇瀚奸梻鍌欑贰閸嬪棝宕戝☉銏″殣妞ゆ牗绋掑▍鐘炽亜閺嶃劎鐭岀痪鎯с偢閺岀喖鏌囬敃鈧晶顔尖攽椤旂偓鍋侾 闂傚倸鍊峰ù鍥敋瑜嶉～婵嬫晝閸岋妇绋忔繝銏ｅ煐閸旀牠宕戦妶澶嬬厸闁搞儮鏅涘皬闂佺粯甯掗敃銉╁Φ閸曨喚鐤€闁圭偓鎯屽Λ锟犳倵鐟欏嫭绀冮柣鎿勭節瀵鎮㈤崨濠勭Ф闂佸憡鎸嗛崨顔筋啅闂傚倷鑳剁划顖炲礉閺囥垺鏅濇い蹇撶墕閽冪喖鏌ㄥ┑鍡╂Ц缂佺姵绋掗妵鍕冀閵娿倗绻佹繛瀵稿Л閺呯娀骞冨畡閭︾叆闁割偅绻€閸犲﹪姊洪幖鐐插闁告鍟块敃銏＄瑹閳ь剙顫忛搹鍦＜婵☆垱娲橀崹濂稿Φ閹版澘绀冮柕濠傚嚱缁插墽鎹㈠┑鍡╂僵妞ゆ挾濮寸敮?URL闂傚倸鍊搁崐鎼佸磹閻戣姤鍊块柨鏃堟暜閸嬫挾绮☉妯诲櫧闁活厽鐟╅弻鐔衡偓鐢殿焾娴犙囨⒒閸曨偄顏柡宀嬬節瀹曟﹢濡搁妷銏犱壕闁告縿鍎查浠嬫煏閸繃鍟掗柡鍐ㄧ墛閺呮煡鏌涘☉鍗炲箺婵炲牜鍘剧槐鎾存媴閸濆嫅顒併亜椤愩埄妲洪柟骞垮灩閳规垿宕堕埡鍐闂備胶顭堥張顒傜矙閹存績鏋嶉柨婵嗩槹閳锋垹绱掔€ｎ偒鍎ラ柛搴＄箳缁辨帗寰勭仦鎯ф畬濡炪値鍋勭换鎴犳崲濠靛棭娼╂い鎺戝亞濡茬増淇婇悙顏勨偓鏍箰妤ｅ啫纾婚柣鏃傚帶閸屻劍銇勯幇鈺佺労婵炴挸顭烽弻鏇㈠醇濠靛浂妫￠梺缁樻尪閸庣敻寮婚敐澶樻晣闁绘劖绁撮崑鎾诲箹娓氬洦鏅梺鎸庣箓椤︿即宕戦妸褏纾奸悗锝庡幗绾爼鏌涢弮鎾剁暠妞ゎ亜鍟存俊鍫曞幢濡ゅ啰鎳嗛梻浣侯焾閿曘劌鐣烽崹顐ょ彾闁哄洨鍠撻梽鍕煕濞戞﹫鍔熼柛妯哄船椤啴濡堕崱妤€顫庨梺鍝ュУ閸旀瑥鐣烽搹顐㈩嚤閻庢稒顭囬崢鍛婄箾鏉堝墽鎮兼い顓炵墦椤㈡棃顢橀悢绋垮伎婵犵數濮撮崯顖炲Φ濠靛洢浜滈柕蹇婂墲缁€瀣攽椤旂懓浜鹃梻浣稿暱閹碱偊宕愰幇顖ｆЬ缂備浇椴搁幐鑽ょ箔閻旂厧鐐婇柍杞扮瀵姊绘笟鈧鑽ょ礊閸モ晝绀婂ù锝呮憸閺嗭妇鎲搁悧鍫濈瑨缂佺姵姘ㄩ幉鍛婂緞閹邦剛鍝楁繛瀵稿Т椤戝棝鎮￠弴鐔翠簻闁规壋鏅涢埀顒佹礋瀵悂寮崼鐔哄幐闂佺硶鍓濋悷銉╁煝閸喐鍙忓┑鐘插暞閵囨繄鈧娲﹂崑濠傜暦閻旂⒈鏁冮柨娑樺閺呭ジ姊婚崒娆戭槮闁硅绻濋幃鐑藉Ψ閳轰胶鏌堥梺鍦檸閸犳艾鐣垫担閫涚箚闁靛牆瀚崗宀勬⒒閸曨偄顏紒杈ㄥ笧閳ь剨缍嗛崣搴ㄥ吹?     */
    private List<SourceCandidate> retainUnverifiedHttpFallbackCandidatesIfNeeded(CollectorNodeConfig config,
                                                                                 SupplementExecutionOutcome supplementOutcome,
                                                                                 CandidateVerificationResult verificationResult) {
        List<SourceCandidate> updatedCandidates = verificationResult == null || verificationResult.getUpdatedCandidates() == null
                ? List.of()
                : verificationResult.getUpdatedCandidates();
        if (!shouldRetainUnverifiedHttpFallback(config, supplementOutcome, verificationResult)) {
            return updatedCandidates;
        }
        return updatedCandidates.stream()
                .map(candidate -> candidate == null ? null : candidate.toBuilder()
                        .selectionStage("SUPPLEMENTED")
                        .selectionReason("supplement candidates promoted after HTTP fallback returned usable results")
                        .selectionSummary("HTTP fallback added candidates to the selection pool")
                        .build())
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private boolean shouldRetainUnverifiedHttpFallback(CollectorNodeConfig config,
                                                       SupplementExecutionOutcome supplementOutcome,
                                                       CandidateVerificationResult verificationResult) {
        if (config == null || !Boolean.FALSE.equals(config.getBrowserSearchEnabled())) {
            return false;
        }
        if (supplementOutcome == null || !supplementOutcome.isProviderFallbackUsed()) {
            return false;
        }
        if (verificationResult == null
                || verificationResult.getUpdatedCandidates() == null
                || verificationResult.getUpdatedCandidates().isEmpty()) {
            return false;
        }
        return verificationResult.getVerifiedTargets() == null || verificationResult.getVerifiedTargets().isEmpty();
    }

    private boolean isBlockedDomain(SourceCandidate candidate, List<String> blockedDomains) {
        if (candidate == null || !StringUtils.hasText(candidate.getDomain())
                || blockedDomains == null || blockedDomains.isEmpty()) {
            return false;
        }
        String normalized = candidate.getDomain().toLowerCase(Locale.ROOT);
        return blockedDomains.stream()
                .filter(StringUtils::hasText)
                .map(domain -> domain.toLowerCase(Locale.ROOT))
                .anyMatch(domain -> normalized.equals(domain) || normalized.endsWith("." + domain));
    }

    private String safeSourceType(String sourceType) {
        return StringUtils.hasText(sourceType) ? sourceType.toUpperCase(Locale.ROOT) : "OFFICIAL";
    }

    private String extractDomain(String url) {
        return canonicalUrlResolver.canonicalDomain(url);
    }

    private String toRootUrl(String url) {
        String canonicalUrl = canonicalUrlResolver.canonicalize(url);
        if (!StringUtils.hasText(canonicalUrl)) {
            return null;
        }
        try {
            java.net.URI uri = java.net.URI.create(canonicalUrl);
            if (!StringUtils.hasText(uri.getScheme()) || !StringUtils.hasText(uri.getHost())) {
                return null;
            }
            return uri.getScheme().toLowerCase(Locale.ROOT) + "://" + uri.getHost().toLowerCase(Locale.ROOT);
        } catch (Exception exception) {
            return null;
        }
    }

    private boolean shouldSupplement(CollectorNodeConfig config,
                                     int verifiedCount,
                                     int minVerifiedCount,
                                     int candidateCount,
                                     int targetCount,
                                     boolean resultPageVerificationEnabled) {
        boolean runtimeSearchEnabled = !"HEURISTIC_ONLY".equalsIgnoreCase(config.getSearchMode());
        if (!runtimeSearchEnabled) {
            return false;
        }
        if (hasPendingFieldEvidenceQueries(config)) {
            return true;
        }
        /*
         * official 闂傚倸鍊搁崐鎼佸磹閻戣姤鍤勯柛顐ｆ礀缁犵娀鏌熼幑鎰靛殭閻熸瑱绠撻幃妤呮晲鎼粹€愁潻闂佹悶鍔嶇换鍫ョ嵁閺嶎灔搴敆閳ь剚淇婇懖鈺冩／闁诡垎浣镐划闂佸搫鏈ú妯兼崲濠靛﹦鐤€闁哄洨濮靛▓鍛婁繆閻愵亜鈧牕煤濠靛洢浠堥柛娑橈功閳瑰秴鈹戦悩鍙夌ォ闁轰礁绉甸幈銊ヮ潨閸℃绠洪梺绋垮閹瑰洭寮婚敐澶婎潊闁宠桨鑳舵禒婊堟⒑缁嬫寧鎹ｉ柡浣筋嚙椤曪絿鎷犲ù瀣潔濠殿喗顨呭Λ娆撳磽闂堟侗娓婚柕鍫濇閸у﹪鏌涚€ｎ偅宕岄柡灞剧洴婵″爼宕煎鍐╁創缂傚倷娴囨ご鍝ユ暜濡も偓椤洩绠涘☉妯溾晝鎲稿鍥С妞ぱ咁殙petitorUrls 闂傚倸鍊搁崐宄懊归崶顒夋晪鐟滃繘骞戦姀銈呯婵°倐鍋撶痪鎯ь煼閺岋綁骞囬锝嗏挅濠电偛妯婃禍婊堝礃閳ь剙顪冮妶鍡楀Ё缂傚秴妫楅…鍥偄閸忓皷鎷虹紒缁㈠幖閹冲繗銇愯濮婂宕熼銏╀純閻庤娲樺ú鏍敇閸忕厧绶為悗锝庡墮楠炲牓姊绘担鍛婃儓婵炲眰鍨藉畷婵嗙暆閸曨厼绁﹀┑掳鍊曢崯鎵閼测晝纾藉ù锝夋涧閻忊晠鏌ｈ箛銉ヮ洭闁逞屽墯椤旀牠宕伴弽顓熷亯濠靛倻顭堥弰銉╂煥閻斿搫孝缂佲偓閸愵喗鐓忓┑鐐茬仢閳ь剚顨婇獮鎴﹀即閵忊檧鎷绘繛鎾村焹閸嬫捇鏌嶈閸撴盯宕戝☉銏″殣妞ゆ牗绋掑▍鐘炽亜閺嶎偄浠﹂柣鎾跺枛閺岋綁寮崹顔鹃獓濠电偛鎳庨敃顏堝蓟濞戙垹鐓橀柟顖嗗倸顥氭繝纰夌磿閸嬫垿宕愰弽褜鍟呭┑鐘宠壘绾惧鏌熼悙顒傛殬濞存粍绮撻弻銊╁籍閸ヨ泛娈梺璇茬箞閸庣敻寮婚弴銏犵倞闁靛鍎遍～鎴濐渻閵堝繒鐣辨繝鈧柆宥呯劦?seed闂?         * 闂傚倸鍊搁崐鎼佸磹妞嬪海鐭嗗〒姘ｅ亾妤犵偛顦甸弫鎾绘偐椤旂懓浜鹃柛鎰靛枛瀹告繈鏌℃径瀣仴闁稿绉瑰娲传閸曨厜鐘绘煕閺傛寧鎹ｇ紒顔剧帛閵堬綁宕橀埡鍐ㄥ箞闂備線娼ч¨鈧紒鑼跺Г娣囧﹪鎸婃竟婵堟嚀閳瑰啴宕归鐟颁壕闁哄稁鍋呭畷鍙夌箾閹存瑥鐏╂鐐灪娣囧﹪濡堕崟顓炲閻庤娲樻繛濠傤潖?seed 闂傚倸鍊峰ù鍥敋瑜嶉～婵嬫晝閸岋妇绋忔繝銏ｅ煐閸旀牠宕戦妶澶嬬厸闁搞儮鏅涘皬闂佺粯甯掗敃銉ф崲濞戙垹骞㈡俊顖濇娴犳挳姊洪幖鐐插缂佽鐗撳璇差吋婢跺﹦鍘告繛杈剧到閹诧繝鎮橀幘鏂ユ斀闁绘劘灏欐晶銏ゆ煛閸滀礁浜伴柛鈹惧亾濡炪倖宸婚崑鎾绘煕濡崵鐭掔€规洘鍨块獮妯肩磼濡厧骞堥梻浣筋潐濠㈡﹢宕ラ埀顒傜磼閵娿儱鎮戦柕鍥у椤㈡洟濮€閳跺灕鍕弿濠电姴瀚敮娑氱磼濡ゅ啫鏋涚€规洘鍎奸ˇ杈╃磼閵娿儱鎮戠紒缁樼洴閺佹劙宕ㄩ閿晬婵犵數鍋涢幏鎴犵礊娓氣偓閻涱噣骞嬮敃鈧～鍛存煏閸繃鍣芥い锔哄妼椤啴濡堕崱姗嗘⒖婵犳鍠撻崐鏇㈠煝瀹ュ鍐€妞ゆ挾鍠撻崢浠嬫椤愩垺澶勬繛鍙夌墬缁傛帡鍩￠崨顔惧幈闁诲函缍嗛崑鍕叏瀹ュ鐓欐い鏍ㄧ懅椤︼附銇勯幘鍐叉倯鐎垫澘瀚禒锕傛寠婢跺苯顕遍梻鍌氬€烽悞锕傚箖閸洖纾挎繝濠傜墕缁€瀣亜閹板墎鎮奸柡鍡╁墴濮婂宕掑▎鎴犵崲濠电偘鍖犻崗鐐☉铻栭柛鎰ㄦ櫅鎼村﹪姊洪崷顓炲妺妞ゃ劌鎳愮划?PUBLIC_SEARCH/Tavily 闂傚倸鍊搁崐鎼佸磹妞嬪海鐭嗗〒姘ｅ亾妤犵偞鐗犻、鏇㈡晜閽樺缃曢梻浣虹《閸撴繈鎽傜€ｎ喖鐐婃い鎺嗗亾缂佺嫏鍥х閻庢稒蓱鐏忎即鏌℃担鍝勨枅婵﹥妞介弻鍛存倷閼艰泛顏繝鈷€灞芥珝婵☆偂鐒﹀鍕箛椤撶姴骞堥梻濠庡亜濞诧妇绮欓幒妤佹櫖闁绘棁顔栭悷鎵虫敠闁割煈鍠氭导鍫ユ⒑閸︻収鐒鹃柨鏇ㄤ邯楠炲啴濮€閵堝懐顦ч梺鍏肩ゴ閺呮盯鐛崼銉︹拻濠电姴楠告禍婊勭箾鐠囇呯暤妤犵偞鍔栫换婵嗩潩椤掑嫭锛楅梻浣稿悑娴滀粙宕曢娑氼洸婵犲﹤鐗婇悡娆撴倵濞戞瑯鐒界紒鐘崇墪椤法鎹勯崫鍕典紑缂備浇椴哥敮鐐哄焵椤掑﹦绉靛ù婊嗘硾鍗遍柛锔诲幐閸嬫捇宕归锝囧嚒闁诲孩鍑归崳锝夊春閳ь剚銇勯幒鎴姛缂佸鏁婚弻娑氣偓锝庝簼椤ャ垻鈧娲忛崹钘夌暦瑜版帩鏁冮柕鍫濇祩閸熷酣姊绘担鐑樺殌妞ゆ洦鍙冨畷鎴︽倷閸忓摜鍓ㄥ銈嗘尪閸ㄦ椽宕愰崹顐ょ闁瑰鍋涚粭姘箾閸涱叏鏀婚柕鍥у婵偓闁宠棄妫欓悾璺侯渻閵堝骸骞戦柛鏃€鍨甸悾鐑芥偂鎼存ɑ鏂€闂佹悶鍎撮崺鏍夐妶澶嬧拻闁稿本鐟ч崝宥夋煙椤旇偐鍩ｇ€规洘娲熼、娑㈡倷閼碱剦妲烽柣搴＄畭閸庡崬煤閵娿儙娑㈩敍濞戞牔绨婚梺鍝勭Р閸斿矂鎮炵憴鍕箚闁圭粯甯炴晶锕傛煛瀹€鈧崰鏍嵁閸℃凹妲鹃梺鍦櫕婵妲愰幒妤佸殝闁汇垽娼у銊╂⒑閸濆嫮鐒跨紓宥勭窔瀵偊宕掗悙鏉戜患閻庡厜鍋撻柍褜鍓熼幃鈩冨緞閹邦厸鎷洪柣鐘叉礌閳ь剙纾禒鈺呮⒑閸濄儱鏋戞繛鍏肩懇閹箖鎮滈懞銉ヤ簻缂佺偓濯芥ご鎼佸疾閵忥紕绠鹃柟鐐綑閻掑綊鏌涚€ｎ偅灏板ǎ鍥э躬楠炲棜顦叉俊鎻掝煼閺屽秶鎲撮崟顐や紝闂佽鍠掗弲娑㈠煝鎼淬倗鐤€闁瑰灝鍟╅幃锝呪攽閻樻剚鍟忛柛鐘崇墵瀹曟劙宕稿Δ鈧拑?         */
        if (isSearchFirstDirectDiscoverySeedMode(config)) {
            return true;
        }
        if (shouldSkipSupplementForDirectDiscovery(config, verifiedCount, minVerifiedCount)) {
            return false;
        }
        if (resultPageVerificationEnabled && Boolean.TRUE.equals(config.getVerifyCandidates())) {
            return verifiedCount < minVerifiedCount;
        }
        return candidateCount < targetCount;
    }

    private boolean isSearchFirstDirectDiscoverySeedMode(CollectorNodeConfig config) {
        return config != null
                && searchPolicyResolver.isSearchFirstSourceFamilyForSourceType(config.getSourceType())
                && (config.getSourceCandidates() == null || config.getSourceCandidates().isEmpty())
                && config.getCompetitorUrls() != null
                && !config.getCompetitorUrls().isEmpty();
    }

    private boolean hasPendingFieldEvidenceQueries(CollectorNodeConfig config) {
        return config != null
                && config.getDimensionEvidencePlan() != null
                && config.getDimensionEvidencePlan().hasPendingFieldEvidenceQueries();
    }

    /**
     * direct discovery 闂傚倸鍊峰ù鍥敋瑜嶉～婵嬫晝閸岋妇绋忔繝銏ｅ煐閸旀牠宕戦妶澶嬬厸闁搞儮鏅涘皬闂佺粯甯掗敃銉ф崲濞戙垹骞㈡俊顖濇娴犳挳姊洪幖鐐插缂佽鐗撳濠氬Ω閳哄倸浜滈梺鍛婄箓鐎氬懘濮€閵忋垻锛?stable locator 闂傚倸鍊搁崐宄懊归崶顒夋晪鐟滃繘骞戦姀銈呯婵°倐鍋撶痪鎯ь煼閺岋綁骞囬锝嗏挅濠电偛妯婃禍婊堝礃閳ь剙顪冮妶鍡楀Ё缂傚秴妫楅…鍥偄閸忓皷鎷虹紒缁㈠幖閹冲繗銇愯缁辨帡鎮╅崘鑼患缂備緡鍠栭…鐑藉极閹邦厼绶為悗锝庡墮楠炲秹姊婚崒娆戣窗闁告瑥绻掔划濠氬箣閻愬瓨鐝烽梺瑙勵問閸犳氨澹曢悡搴唵閻犺桨璀﹂悞楣冩煕鐎ｃ劌鐏查柡?sourceType 闂傚倸鍊搁崐鎼佸磹閻戣姤鍊块柨鏇炲€归崕鎴犳喐閻楀牆绗掔紒鈧径灞稿亾閸忓浜鹃梺閫炲苯澧撮柛鈹惧亾濡炪倖甯婄粈渚€宕甸鍕厱婵炲棗绻愰弳锝夋煏閸℃洜顦﹂摶鏍煃瑜滈崜鐔煎灳閿曞倸惟闁宠桨鑳堕ˇ銊╂⒑鐠団€崇€婚柛鏇㈡涧閹亪姊婚崒姘偓鎼佲€﹂鍕；闁告洦鍊嬪ú顏呮櫇闁稿本姘ㄩ鍥⒑閸︻叀妾搁柛鐘崇墱婢规洘绻濆顓犲幍闂佺粯鍔﹂崜姘舵倶闁秵鐓涢悗锝庝邯閸欏嫰鏌″畝鈧崰鎾舵閹烘顫呴柣妯哄悁閸濇鈹戦悙鑼憼缂侇喖绉瑰畷鏇㈠箮鐟欙絺鍋撻敂鐐磯闁靛绠戠壕顖涚箾閹炬潙鍤柛銊ゅ嵆瀹曟粓宕￠悜鍡樺瘜闂侀潧娴傞崹顖滅矆娴ｈ娲晝閸屾稑浜?     * 闂傚倸鍊搁崐鎼佸磹妞嬪海鐭嗗〒姘ｅ亾妤犵偛顦甸弫鎾绘偐閸愯弓鐢绘俊鐐€栭悧妤冪矙閹炬眹鈧懘寮婚妷锔惧弳濠电娀娼уΛ顓炍ｉ崼鏇熺厓鐟滄粓宕滃▎鎾崇疇婵せ鍋撶€殿噮鍋婂畷姗€顢欓懖鈺嬬床婵犳鍠楅敃鈺呭礈濞戞嚎浜瑰鑸靛姈閳锋帒銆掑锝呬壕闂侀€炲苯澧伴柛瀣洴閹崇喖顢涘☉娆愮彿闂佸搫顦伴敍鏇㈡偡闁妇鍙嗛梺鍛婃处閸樹粙骞夊▎鎴犵＝濞达絽鎼牎缂備礁顑嗛崹鍧楀春閵夛箑绶炲┑鐘插閸嶉潧顪冮妶鍡楃瑨闁稿﹤顭峰畷銏ゎ敆閸曨兘鎷婚梺绋挎湰閻熝囧礉瀹ュ棔绻嗛柣鎰綑椤曟粎绱掗崒姘毙ч柟顔界懇瀹曨偊宕熼鐘茬倞闂傚倷娴囬～澶婄暦濡偐鐭撴い鏇楀亾闁诡喗绮岃灒濞撴凹鍨板▓濂告⒒娴ｅ憡鎯堟い锔垮嵆瀹曞綊鎮界粙鑳煘闂侀潧艌閺呮粓鍩涢幋锔界厵闁兼祴鏅涙禒婊堟煕閺傚搫浜鹃梻鍌欑閹诧繝宕濋幋锕€绀夌€广儱顦介弫鍥煠濞村娅囩痪鍙ョ矙閺屾稓浠﹂幑鎰棟闂侀€炲苯澧存い銉︽尵閸掓帡宕奸悢绋款€撻梺缁樺灦閿氶幖鏉戯躬濮婃椽鎮烽幍顔芥喖缂備浇顕х粔鐟扮暦閻㈢鍗抽柣妯哄暱閺嬫垿妫呴銏″缂佸鍨圭划鏄忋亹閹烘挾鍘遍梺鍦亾椤ㄥ懘骞婂鈧崺鐐剁疀濞戞瑢鎷洪梻鍌氱墐閺呮盯鎯佸鍫熺厱婵せ鍋撶紒鐘崇墳濡喎顪冮妶鍡樼５闁稿鎹囬弻鈥崇暆鐎ｎ剛袣缂備胶濮甸惄顖炵嵁濮椻偓瀹曟粍绗熼崶褎娅忓┑鐘垫暩閸嬫盯鎮洪妸褍鍨濋柣妯款嚙缁€鍫熺箾閸℃ê鐏ョ€殿喗鐓″缁樻媴娓氼垱缍婇梺鍛婃礀閻忔岸鎮鹃崜浣虹＝濞达絽鎼牎婵犵數鍋涢敃顏勵嚕婵犳艾鍗抽柣鏃囨瑜版儳顪冮妶鍡欏缁炬澘绉瑰畷鍐裁洪鍛偓鍫曠叓閸ャ劍鈷掔紒鐘靛仦閹?search supplement闂傚倸鍊搁崐鎼佸磹閻戣姤鍊块柨鏃堟暜閸嬫挾绮☉妯诲櫧闁活厽鐟╅弻鐔告綇妤ｅ啯顎嶉梺绋垮椤ㄥ﹪寮诲☉姘勃闁告挆鈧Σ鍫濐渻閵堝棙绀嬪ù婊冪埣瀵顓兼径濠佺炊闂佸憡娲﹂崜娆忊枍閿濆洨纾藉ù锝嗗絻娴滈箖姊虹粙鎸庢拱濠㈣娲熷畷鎴﹀箻閼姐倕绁﹂梺鍓茬厛閸犳牗鎱ㄦ惔銊︹拺闁荤喐婢橀弳閬嶆⒑鐢喚鍒版い鏇秮楠炴捇骞掗崱妯尖偓濠氭⒑閸︻厼浜炬繛鍏肩懄缁?public search 闂傚倸鍊搁崐鎼佸磹妞嬪孩顐芥慨姗嗗墻閻掍粙鏌ゆ慨鎰偓妤冪矆婵犲洦鐓曢柍鈺佸枤閻掕姤銇勯埡浣哥骇闁靛洤瀚粻娑㈠箻鐠轰警鏉搁梻浣侯焾椤戝懘宕愰崸妤€钃熸繛鎴欏灩缁犲鏌ょ喊鍗炲濠碘€茬矙濮婅櫣绮欏▎鎯у壉闂佹寧娲忛崹钘夘嚕婵犳艾鍗抽柣鏃堫棑缁愮偛鈹戦悙鏉戠仸闁挎洍鏅涚叅妞ゆ挶鍨洪埛鎴犵磽娴ｈ鐒藉褔娼ч湁婵犲﹤鍟伴崺锝団偓?     */
    private boolean shouldSkipSupplementForDirectDiscovery(CollectorNodeConfig config,
                                                           int verifiedCount,
                                                           int minVerifiedCount) {
        if (config == null) {
            return false;
        }
        /*
         * 闂傚倸鍊搁崐鎼佸磹閻戣姤鍤勯柛顐ｆ礀缁犵娀鏌熼幑鎰靛殭閻熸瑱绠撻幃妤呮晲鎼粹€愁潻闂佹悶鍔嶇换鍫ョ嵁閺嶎灔搴敆閳ь剚淇婇懖鈺冩／闁诡垎浣镐划闂佸搫鏈ú妯兼崲濠靛﹦鐤€闁哄洨濮靛▓鍛婁繆閻愵亜鈧牕煤濠靛洢浠堥柛娑橈功閳瑰秴鈹戦悩鍙夌ォ闁轰礁绉甸幈銊ヮ潨閸℃绠洪梺绋垮閹瑰洭寮婚敐澶婎潊闁宠桨鑳舵禒婊堟⒑缁嬫寧鎹ｉ柡浣割煼閻涱噣宕橀妸搴㈡瀹曟﹢鍩℃担绋跨闂傚倷绀侀幉鈩冪瑹濡ゅ懎鍌ㄥΔ锝呭暙閸屻劍銇勯幇銊﹀櫚闁衡偓娴犲鐓冮柦妯侯槹椤ユ粌霉濠婂嫮鐭掗柡宀嬬節瀹曞ジ顢曢姀鐙€娼剧紓鍌欑贰閸犳鎮烽敃鈧銉╁礋椤栨氨鐤€闂佸壊鐓堥崑鍛枍瀹ュ鈷掑〒姘ｅ亾婵炰匠鍡楁闂備礁鎲＄划鍫ユ倿閿曗偓閺嗐兙irect discovery 闂傚倸鍊搁崐鎼佸磹妞嬪海鐭嗗〒姘ｅ亾妤犵偛顦甸弫鎾绘偐閸愯弓鐢绘俊鐐€栭悧妤冪矙閹炬眹鈧懘宕ｆ径宀€鐦堥梻鍌氱墛缁嬫帡鎮炴ィ鍐╃叆婵犻潧妫Σ鍝ョ磼閻樺灚鍤€闂囧鏌ㄥ┑鍡樺櫤闁规彃鎲￠妵鍕棘閹稿骸娅ょ紓浣虹帛缁诲牓骞冩禒瀣棃婵炵缈伴崹浠嬪箖濡も偓椤繈鎮℃惔锛勭潉闁诲孩顔栭崰鏍偪閸モ晝涓嶆繛鎴炃氬Σ鍫熺箾閸℃ê鐏ユ鐐茬Ч濮婄粯鎷呴崨濠呯闂佹儳绻愰柊锝呯暦閹剁瓔鏁婇柛銏狀槹閻╊垶鐛€ｎ喗鍋愰柣銏㈩暜缁卞弶淇婇悙顏勨偓鏍涙笟鈧、姘愁槻闁伙絿鏁诲畷鍗炩枎鐏炴垝澹曢柣鐔哥懃鐎氼厾绮堥崘顔界厽闁绘柨寮跺▍濠冾殽閻愬澧电€规洜鍏橀、姗€鎮㈤柨瀣偓顓㈡⒒娴ｅ憡鍟炴繛璇х畵瀹曘垽鎸婃径鍡樼亖闂佸憡绺块崕宕囧娴犲鐓曢悘鐐插⒔閹冲懏銇勯敂濂告闁靛洤瀚伴獮瀣攽閸パ勭暬闁诲氦顫夊ú锕傚垂鐠鸿櫣鏆︾紒瀣嚦閺冣偓閹峰懘姊归幇顓炰簼濠电姴鐥夐弶搴撳亾瑜忓濠冪鐎ｎ亞鏌堝銈嗗姂閸婃鎯岄崱娑欑厱鐎光偓閳ь剟宕戦悙鐑樺亗闊洦鎼╅悢鍡涙偣妤︽寧顏犲褎娲熼弻娑㈠籍閳ь剙顫濋妸褎顫曢柟鐐墯閸氬鏌涢埄鍏狀亞澹曢幎鑺モ拺闁告繂瀚悘閬嶆煕閻樿櫕宕岀€规洜澧楃换婵嬪磻閻ｅ苯鏋旂紒杈ㄥ笒铻ｉ柣鎾虫捣閸掍即姊婚崒姘偓鐑芥倿閿曗偓椤啴宕稿Δ鈧壕濠氭煕閺囥劌骞橀柣顓熸尵閹叉悂鎮ч崼婵堫儌閻庤鎸风欢姘跺箖濡ゅ懏鏅查幖绮瑰墲閻忓牏绱撴担鍝勑ｇ紒瀣浮婵＄敻宕熼姘鳖吅闂佹寧绻傚Λ顓炍涢崟顒傜閻庢稒顭囬惌宀勬煕鐎ｎ偅灏甸柟骞垮灩閳藉濮€閻樿尪鈧灝鈹戦埥鍡楃仴妞ゆ泦鍥ㄥ剭闁硅揪闄勯埛?seed闂?         * 闂傚倸鍊搁崐宄懊归崶顒夋晪鐟滃秹婀侀梺缁樺灱濡嫮绮婚悩缁樼厵闁硅鍔﹂崵娆撴煟閵堝骸娅嶉柡灞界Ч瀹曨偊宕熼锝嗩啀缂傚倷鑳舵慨鐢稿蓟閵娾斁鈧箓宕稿Δ浣告疂闂傚倸鐗婄粙鎴︼綖瀹ュ鈷戦柟鑲╁仜閳ь剚娲滈埀顒佺煯閸楀啿顕ｆ繝姘櫇闁逞屽墲閻忔帗绻涢幘鏉戝毈闁搞劏浜悷褔姊婚崒娆戭槮闁圭⒈鍋婇獮濠呯疀濞戞瑥浠梺鍐叉惈閹冲酣鎷戦悢鍝ョ闁瑰瓨鐟ラ悘鈺呭船椤栫偞鍋℃繝濠傚暟瀛濋梺娲荤厛閸撶喎顫忓ú顏呭殥闁靛牆鎲涢敐澶嬬厱闁哄啠鍋撴い銊ワ工閻ｉ攱瀵奸弶鎴濆敤濡炪倖鎸鹃崑娑㈡倵椤撱垺鈷戦悹鍥ｂ偓宕団偓鑽も偓鍏夊亾闁逞屽墴瀹曪綁宕卞☉娆屾嫼闂佸憡鎸昏ぐ鍐╃閺嶎厽鐓曢柕濞垮劤缁夋椽鏌熼姘冲閾绘牠鏌涘☉鍗炴灓闁告ü绮欏铏圭磼濡浚浜滈锝夊醇閺囩喎鍓瑰┑掳鍊曢幊蹇涙偂閻斿吋鐓欓柧蹇曟嚀娴犙囨煟閿濆洦鏆╅柍褜鍓氶鏍窗濡や胶绠惧┑鐘叉搐缁犳牠鏌曡箛瀣偓鏇犵矆閸岀偞鐓犳繛鏉戭儐濞呭懘鎮介娑欏磳婵﹦绮换婵囨償閳ヨ尙鐩庢繝鐢靛仩椤曟粍淇婇崶顒€绐楀┑鐘插亞閸氬鏌涢锝団棩婵顨婇幃宄邦煥閸曨剦妫冮悗娈垮枛椤兘宕规ィ鍐ㄧ疀濞达絽鎲￠崐顖炴⒒娴ｅ憡鍟炲〒姘殜瀹曟澘顫濈粩顖氭喘閺屽棗顓奸崱蹇斿闂傚倷绶￠崑鍡涘磻濞戙垺鍤愭い鏍ㄧ⊕濞呯姵銇勯弽銊х煂缁炬儳銈搁幃褰掑炊瑜嶇痪褎銇勯妷锝呯伈闁哄本鐩崺鐐哄箚瑜屾竟鏇炩攽閿涘嫬浜奸柛濠冪墪椤斿繑绻濆顒傦紱闂佺懓澧界划顖炴偂閻旀悶浜滈柟鎹愭硾娴犳粓鏌嶈閸撴岸鎮洪弴銏″€堕柟鐑橆殕閳锋垹绱掗娑欑濠⒀嗗皺缁辨帞鈧綆鍋勯悘銉╂懚?PUBLIC_SEARCH/Tavily 闂傚倸鍊搁崐宄懊归崶褏鏆﹂柣銏㈩焾绾惧鏌ｉ幇顔芥毄闁活厽鐟╅悡顐﹀炊閵娧€妲堢紒鐐劤濞硷繝寮婚妶鍥ф瀳闁告鍋涢埛澶嬬節濞堝灝鏋︽繛鍛礋婵＄敻宕熼姘祮濠德板€愰崑鎾趁瑰鍫㈢暫婵﹦鍎ゅ顏堝箥椤曞懏袦缂傚倷绀侀ˇ鎶藉春閺嶎偆鐭夐柟鐑橆殕閺呮繈鏌涚仦鍓р槈婵炲牏鍠栧娲濞戣鲸肖闂佺姘︽ご鎼佸疾閼哥偣鍋呴柛鎰ㄦ杹閹风粯绻涙潏鍓у埌闁硅绻濆畷顖炴倷閻戞ê浠哄銈嗙墬娓氭鈻撳鍫熺厵妞ゆ梹鍎抽崢鏉戔攽閳ュ磭鍩ｇ€规洖鐖奸垾锕傚箣閻愮數鈼ュ┑鐘垫暩閸嬬偛顭囧▎鎾宠Е閻庯綆浜堕悞鑺ョ箾閸℃ɑ灏伴柛濠傚槻閳规垿鎮╅崣澶婎槱闂佺粯鎸鹃崰鏍蓟閻斿吋鐒介柨鏇楀亾妤犵偞鐗滅槐鎺楀箵閹烘繄鍚嬮梺鍝勭焿缂嶄線鐛鈧畷妯好圭€ｎ亙澹曢梺绉嗗嫷娈旂紒鐘崇墬娣囧﹪濡堕崨顓熸闂佸摜濮村Λ婵嬪蓟濞戙垹鍗抽柣妯挎珪濮ｅ嫰姊洪崨濠冾棃妞ゃ儲鎸惧Σ鎰板箻鐠囪尙锛滃┑顔缴戦惁鐑藉鎺虫禍婊堟煥閺冨洦顥夋い銉ヮ儑缁辨帡顢欑憴鍕彋闂佽鍠撻崹浠嬪箖閳╁啯鍎熼柨婵嗛閺佽埖绻濋悽闈浶ｆい鏃€鐗犲畷鏉课旈崨顓狀槷闂婎偄娲︾粙鎴犲婵犳碍鐓欓柣鎰靛墯缂嶆垹绱掗崜浣镐槐闁哄瞼鍠栭弻鍥晝閳ь剟鐛弽顓熺厱闁规崘鍩栭崵鈧銈庝簻閸熷瓨淇婇崼鏇炲耿婵☆垵顕ч崜鐢电磽閸屾瑦绁板瀵割焾鐓ら柣鏃傚帶缁犳牠鏌曡箛瀣偓鏇烆啅濠靛鍊垫繛鎴炵懐閻掍粙鏌涘Ο鍏兼毈婵﹨娅ｇ划娆戞崉閵娧屽晥闂備胶顭堥鍡涘礉濞嗘挸绠氶悘鐐垫櫕閺嗗棝鏌涢弴銊ュ闁告﹢浜堕弻锝堢疀閺囩偘鍝楀銈嗘肠閸曨剙寮块梺绋跨灱閸嬬偤鎮″▎鎰╀簻闁哄秲鍔忔竟姗€鏌￠崱顓犳偧闁逞屽墲椤煤濠婂牆绐楅柡宥庡幑閳ь兛绀佽灃濞达綀娅ｉ幊婵囩節閻㈤潧孝閻庢凹鍣ｈ棟妞ゆ挶鍨洪埛鎺楁煕鐏炲墽鎳呮い锔肩畵閺岀喓鍠婇崡鐐板枈濡炪們鍨洪悧鐘茬暦婵傜唯闁靛闄勮ⅲ闂傚倷绀侀幖顐ょ矙娓氣偓瀹曘垺绂掔€ｎ亞锛欓梺鍓茬厛閸嬩焦绂嶅鍫熺厸鐎广儱楠告禍婊兠归悩宕囩煁婵?         */
        if (searchPolicyResolver.isSearchFirstSourceFamilyForSourceType(config.getSourceType())) {
            return false;
        }
        if (config.getSourceCandidates() != null && !config.getSourceCandidates().isEmpty()) {
            return false;
        }
        if (verifiedCount < minVerifiedCount) {
            return false;
        }
        if (config.getCompetitorUrls() == null || config.getCompetitorUrls().isEmpty()) {
            return false;
        }
        return directDiscoveryPlanner.buildInitialCandidates(
                config.getCompetitorName(),
                safeSourceType(config.getSourceType()),
                config.getCompetitorUrls()
        ).stream().anyMatch(candidate ->
                candidate != null && safeSourceType(config.getSourceType()).equals(candidate.getSourceType()));
    }

    /**
     * recovery 闂傚倸鍊搁崐鎼佸磹妞嬪海鐭嗗〒姘ｅ亾妤犵偛顦甸弫鎾绘偐閸愯弓鐢绘俊鐐€栭悧妤冪矙閹炬眹鈧懘宕ｆ径宀€鐦堟繝鐢靛Т閸婃悂寮搁敃鍌涚厾闁绘鐗嗛婊堟煏閸パ冾伂缂佺姵鐩獮妯兼崉娓氼垱姣囬梻鍌欐祰椤曆呮崲濡も偓閳诲秹寮撮悩鍐插簥濠电娀娼уú銊у姬閳ь剟姊虹粙鎸庢拱缁炬澘绉撮埢鎾诲礈娴ｈ櫣锛濇繛杈剧到婢瑰﹤危閹间焦鐓忛柛顐ゅ枑閸婃劗鈧鍠涢褔鍩ユ径鎰潊闁绘ɑ顔栭崯瀣煟鎼淬値娼愭繛鍙夌墪閳绘棃鏁冮崒娑欐珳婵犮垼娉涢鍥储娴犲鈷戦梻鍫熶緱濡插爼鏌涙惔銏㈠弨鐎规洜鏁诲畷鍫曞煘閹傚濠电偛鐗嗛悘婵嬪几濞戙垺鐓熼煫鍥ㄦ婢规ɑ銇勯弴妯哄姦鐎规洜鍠栭、娑橆潩鏉堚晜婢戝┑锛勫亼閸婃牕螞娓氣偓瀹曟垿骞囬悧鍫濅簵闂佺鎻梽鍕煕閹达附鐓犲┑顔藉姇閳ь剚娲栭悺顓熺節閻㈤潧浠╂い鏇熺矌瀵板﹪宕归瑙勭€婚梺闈涚箳婵厼顭囬埡鍛仯濡わ附瀵ч鐘绘煕閺冩挾鐣辨い顏勫暣婵″爼宕卞Δ鍐啰婵犵绱曢崑鐐垫暜濡ゅ啰鐭夌€广儱顦～鍛存煏韫囧鐏柨娑欑矒閺岋綁鎮╅崣澶婎槱閻熸粍婢橀崯鏉戭嚕閹惰姤鏅插璺侯儑閸橀潧顪冮妶鍡欏闁煎綊绠栧鎶芥晲閸ワ絽浜鹃悷娆忓缁€鍫ユ煕閻樺磭澧甸柕鍡曠铻栧ù锝呮憸缁愮偞绻濋悽闈浶㈠ù纭风到铻栭柛娑卞枤閸樹粙鏌熼崗鑲╂殬闁糕晛瀚板畷顖濈疀濞戞瑧鍘遍梺缁樏壕顓熸櫠闁秵鐓欐鐐茬仢閻忓弶顨ラ悙鍙夊枠鐎殿喖澧庨幑鍕Ω閵夈儛鏇炩攽閿涘嫬浜奸柛濠冪墱閺侇喗绻濋崶銊ユ畱闂佸壊鍋呭ú鏍不閻樼粯鐓曢柕澶樺枛婢ь垶鏌ｉ幘鍐叉殶闁硅尙顭堥…銊╁醇濠靛牜妲舵繝鐢靛仜濡瑩骞忛弻銉ョ倞妞ゆ帒顦伴弲婵嬫⒑閹稿孩纾甸柛瀣崌閺岋綁骞掗幋鐘辩驳闂侀潧娲ょ€氫即鐛幒鎴悑闁割偅绻傜敮顖滅磽閸屾瑨鍏屽┑顔炬暬瀹曞綊宕烽鐕佹綗闂佸湱鍎ら崵锕傚籍閳ь剟骞忛崨鏉戜紶闁告洏鍔嶇€氭煡姊虹拠鍙夊攭妞ゎ偄顦叅婵せ鍋撻柟顔惧厴閸╋繝宕ㄩ鐙€鍟囬柣鐔哥矌婢ф鏁Δ鍛；闁跨喓濮甸悡娆愮箾閸繄浠㈤柡瀣ㄥ€濋弻鈩冨緞鐎ｎ偄鈧劖鎱ㄦ繝鍛仩闁归濞€楠炴捇骞掑┑鍡椢ㄧ紓鍌氬€风粈渚€顢栭崱娆愭殰闁跨喓濮磋繚闂佸憡鍔﹂崰鏍嵁閵忥紕绠鹃柟杈剧秮閸濇椽鏌￠崨顏呮珚闁诡喗顨婂畷妤佸緞婵犱礁顥氶梻鍌欑窔閳ь剛鍋涢懟顖涙櫠閹绢喗鐓欐い鏍殔娴滅偓淇婇悙顏勨偓鏇犳崲閹邦優褰掑磼濮ｈ偐鍠愮粭鐔煎焵椤掑嫬钃熼柨鏇楀亾閾伙綁鏌ｉ幘鍐差劉闁诲繐鐗嗛埞鎴︻敊绾攱鏁惧┑锛勫仒缁瑩鎮伴鈧獮妯兼嫚閼碱剦鍞洪梻浣筋潐閸庢娊鎮橀崼銉ユ瀬鐎广儱妫旂换鍡涙煟閹板吀绨婚柍褜鍓氶悧鏇犲弲闂婎偄娲︾粙鎴﹀垂閸屾稓绠剧€瑰壊鍠曠花濠氭煟閵堝鐣洪柡灞剧洴椤㈡洟鏁愰崱娆樻К缂傚倷璁查崑鎾绘煕閹伴潧鏋熼柣鎾冲暣瀵爼鎮欓弶鎴偓婊堟煕韫囨捁瀚伴棁澶愭煟濡搫鏆卞┑顔肩У閹便劍绻濋崘鈹夸虎閻庤娲忛崝宥囨崲濠靛绀嬫い鎺嗗亾婵炲懏顨嗘穱濠囨倷椤忓嫧鍋撻幋锕€绀夌€光偓閸曨剚娅囬梺闈涚墕椤︻偊鍩€椤掍胶澧紒缁樼箞瀹曞爼濡搁妷銏犱壕缂備焦眉缁诲棙銇勯弽顐沪婵炲懏绮庣槐鎺楀Ω閵夘喚鍚嬪┑顔硷龚濞咃絿鍒掑▎鎴炲磯闁靛ě灞芥櫔闂傚倷绀侀幖顐﹀箠閹炬湹鐒婃繛鍡樻尭缁犳煡鏌曡箛瀣偓鏇㈢嵁閵忥紕绠鹃柟瀵稿仧閹冲懘鏌涘Ο鍝勮埞闁宠鍨块幃娆撳矗婢舵ɑ锛侀梻浣告贡椤牓鈥﹀畡閭﹀殨妞ゆ劧绲剧紞鍥煏婵炲灝鈧绮诲鑸碘拺缂備焦锚婵牏鎲搁弶鍨殻闁糕斁鍋撳銈嗗坊閸嬫挻銇勯鐘插幋闁绘侗鍠楀鍕箛椤掑偆鍟嬫俊鐐€栧Λ浣规叏閵堝應鏋嶉柛銉墯閳锋垿姊洪銈呬粶闁兼椿鍨遍弲鍫曞蓟閵夛妇鍘梺鎼炲劘閸斿本鎱ㄥ鍥ｅ亾鐟欏嫭绀冮悽顖ょ節楠炲啫鈻庨幘宕囩厬?     * 闂傚倸鍊搁崐椋庣矆娓氣偓楠炴牠顢曚綅閸ヮ剦鏁冮柨鏇楀亾闁汇倗鍋撶换婵囩節閸屾粌顤€闂佺顑戠换婵嬪蓟閵娾晛鍗抽柣鎰ゴ閸嬫捁銇愰幒鎴狅紱闁诲函缍嗛崰妤呭煕閹寸偑浜滈柟鍝勬娴滃墽绱撴担鍓叉Ч闁瑰憡濞婇獮鍡欎沪鏉炴寧姊归幏鍛村礂閸濄儳娉块梻鍌欑閹碱偆绮旈弻銉ョ閹兼番鍔岀粻鐘裁归敐鍫燁仩缁炬儳銈搁弻鏇熺箾閸喒鍋撻弴鐐垫殼闁糕剝蓱閸欏繐鈹戦悩鎻掓殲闁靛洦绻冮妵鍕閳藉棙鐤侀梺绯曟杹閸嬫挸顪冮妶鍡楃瑨閻庢凹鍙冨畷鎰版嚋閻㈢數鐦堥梻鍌氱墛娓氭宕曡箛娑欑厱閻忕偠顕ф慨鍌炴煛鐏炵偓绀嬬€规洜鍘ч埞鎴﹀炊瑜庡▍褔姊绘担鍛婅础闁稿簺鍊曠叅婵犲﹤鐗婇崑顏堟煃瑜滈崜娆撳煘閹达富鏁婄紒娑橆儑閸斿憡淇婇悙鑼憼闁诡喖鍊块獮鍐ㄎ旈崪浣规櫍闂侀潧绻嗗褔骞忓ú顏呪拺闁煎鍊曢弸鎴炵節閵忊槄鑰挎鐐插暣楠炲鎮╅悽纰夌床婵＄偑鍊栭弻銊╁Χ閹间礁鐭楅柛鏇ㄥ幐閸嬫挾鎲撮崟顒傤槰闁汇埄鍨崜婵囩┍婵犲洤绠瑰ù锝呮憸閸樻悂姊虹粙鎸庢拱缂侇喖鏈幈銊╂偂鎼存ɑ鏂€闂佺粯鍔曞Ο濠囧吹閻斿皝鏀芥い鏃囧Г鐏忥附銇勯姀锛勫⒌鐎规洏鍔戦、妯衡槈濞嗘垟鍋撳ú顏呪拺闂傚牊绋撶粻鍐测攽椤栵絽寮柟顔哄灲瀹曞崬鈽夊▎蹇庡寲濠德板€ч梽鍕偓绗涘浂鏁傞柣妯款嚙閸愨偓闂佹枼鏅涢崯鎵姬閳?about/help/app闂傚倸鍊搁崐鎼佸磹閻戣姤鍊块柨鏃堟暜閸嬫挾绮☉妯诲櫧闁活厽鐟╅弻鐔告綇妤ｅ啯顎嶉梺鎼炲€栭崝鏍Φ閸曨垰鍐€妞ゆ劦婢€缁墎绱撴担鍝勑ｉ柣妤冨█瀵鎮㈤悡搴ｎ唹闂侀€涘嵆濞佳冣枔椤撶姷纾藉〒姘搐娴滄粎绱掓径濠勭Ш妤犵偛鍟抽妵鎰板箳閹存粎鐐婇梻浣告啞濞诧箓宕滃璺虹？闁瑰墽绮悡鐔煎箹濞ｎ剙鐏卞瑙勆戦妵鍕晜閻愵剚姣堥悗娈垮枛椤攱淇婇懜闈涚窞閻庯綆鍋勯崝鎺撲繆閻愵亜鈧牠宕濋幋锕€鍨傜紓浣诡焽椤╂煡鏌熷▓鍨灓缁炬崘妫勯湁闁挎繂鐗婇鐘绘偨椤栨稓鈯曢柟渚垮妼铻ｇ紒瀣仢椤洤螖閻橀潧浠滅紒缁橈耿楠炲啴鍩￠崨顔藉劒濡炪倖鍔戦崺鍕焵椤掍焦宕屾慨濠呮缁辨帒螣閾忛€涙闂備焦瀵уú宥夊疾濠婂懐鐭夐柟鐑橆殢閺佸鏌嶈閸撶喖鐛崘銊ф殝闂侇叏濡囬崣鍡涙⒑閸涘﹣绶遍柛娆忛叄瀵娊顢橀悙鈺傛杸闂佺粯鍔栬ぐ鍐棯瑜旈弻锝呂旈崘銊㈡瀰閻庢鍣崑鍕敇閸忕厧绶炲┑鐘插閸熷绻濋悽闈涘壋缂傚秴妫濆畷妤€鈽夊鍐残℃繝鐢靛У绾板秹鍩涢幋鐘电＜閻庯綆鍘界涵鍓佺磼閻樺崬宓嗛柡宀€鍠栭幊鐐哄Ψ瑜忛悡澶愭⒑鐠団€虫灈闁搞垺鐓￠崺鐐哄箣閿曗偓缁愭鏌曡箛鏇炐ョ紒澶庢閳ь剝顫夊ú姗€銆冩繝鍌滄殾婵°倕鎳忛崵鍐煃閸濆嫬浜為柛姘搐閳规垿鎮╅崹顐ｆ瘎闂佺顑囬崑鐘诲Φ閹版澘绀冩い顓熷灩閸旂兘姊虹捄銊ユ灁濠殿喖鍢查悾鍨瑹閳ь剟寮婚悢鍏煎€绘慨妤€妫欓悾鍫曟煕閻戝棗鐏﹂柟顔煎槻椤劑宕橀鍡╁敹闂備礁纾划顖毼涢崘鈺傚弿闁逞屽墴閺屾洟宕煎┑鍥舵￥闂佸憡锚閹诧紕鎹㈠┑瀣潊闁挎繂妫涢妴鎰版⒑閸忓吋銇熼柛銊ㄦ硾椤曪綁宕ㄦ繝鍐ㄥ妳闂侀潧绻堥崹濠氭儊閸儲鈷戦梺顐ゅ仜閼活垱鏅剁€涙ǜ浜滈柕蹇婂墲椤ュ牏鈧娲栧畷顒勫煝鎼淬劌绠ｉ柣妯诲絻缁犳稒绻濋悽闈浶ラ柡浣规倐瀹曟垵鈽夊Ο婊呭枑缁绘繈宕橀宥嗛敜闂備礁鎼粙渚€宕㈤懖鈺侇棜濠电姵纰嶉悡娆撴煟閹伴潧澧紓宥嗗灩缁辨帡宕滄担闀愭闂佽鍠楅〃濠囧极閹邦厽鍎熼柍銉﹀墯濞奸箖姊绘担铏广€婇柡鍛〒缁棃鎮烽柇锔界稁?     */
    private boolean shouldTriggerPublicEvidenceRecovery(CollectorNodeConfig config,
                                                        List<SourceCandidate> candidates,
                                                        Map<String, SearchCollectionTarget> attemptedTargets) {
        List<SourceCandidate> safeCandidates = candidates == null ? List.of() : candidates;
        boolean hasVerifiedCandidate = safeCandidates.stream()
                .anyMatch(candidate -> candidate != null && Boolean.TRUE.equals(candidate.getVerified()));
        if (hasVerifiedCandidate && (!hasUnmetRequiredFieldEvidencePath(config)
                || hasVerifiedFieldEvidenceCandidate(safeCandidates))) {
            return false;
        }
        if (StringUtils.hasText(config.getRecoveryFieldName())
                || StringUtils.hasText(config.getRecoveryEvidencePathKey())
                || (config.getRecoveryQueryIntents() != null && !config.getRecoveryQueryIntents().isEmpty())) {
            return true;
        }
        if (hasVerifiedCandidate && hasUnmetRequiredFieldEvidencePath(config)) {
            return true;
        }
        if (attemptedTargets == null || attemptedTargets.isEmpty()) {
            return false;
        }
        return attemptedTargets.values().stream().anyMatch(target ->
                target != null && candidateOwnershipPolicy.isUtilityGatePage(
                        target.getCandidate(),
                    target.getCollectedPage()
                ));
    }

    private boolean hasVerifiedFieldEvidenceCandidate(List<SourceCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return false;
        }
        return candidates.stream().anyMatch(candidate -> candidate != null
                && Boolean.TRUE.equals(candidate.getVerified())
                && StringUtils.hasText(candidate.getFieldName())
                && StringUtils.hasText(candidate.getEvidencePathKey())
                && (StringUtils.hasText(candidate.getFieldEvidenceQueryFingerprint())
                || (candidate.getSourceUrls() != null && !candidate.getSourceUrls().isEmpty())));
    }

    private boolean hasUnmetRequiredFieldEvidencePath(CollectorNodeConfig config) {
        if (config == null || config.getDimensionEvidencePlan() == null) {
            return false;
        }
        return config.getDimensionEvidencePlan().hasUnmetRequiredFieldPath();
    }

    private boolean isResultPageVerificationEnabled(CollectorNodeConfig config) {
        if (config.getVerifyResultPage() != null) {
            return Boolean.TRUE.equals(config.getVerifyResultPage());
        }
        if (config.getSearchRuntimePolicy() != null && config.getSearchRuntimePolicy().getVerifyResultPage() != null) {
            return Boolean.TRUE.equals(config.getSearchRuntimePolicy().getVerifyResultPage());
        }
        return true;
    }

    private boolean isTimedOut(long startedAt, long timeoutMillis) {
        return timeoutMillis >= 0 && System.currentTimeMillis() - startedAt >= timeoutMillis;
    }

    private List<SourceCandidate> mergeCandidateUpdates(List<SourceCandidate> currentCandidates,
                                                        List<SourceCandidate> updatedCandidates) {
        if (updatedCandidates == null || updatedCandidates.isEmpty()) {
            return currentCandidates;
        }
        Map<String, SourceCandidate> merged = new LinkedHashMap<>();
        for (SourceCandidate candidate : currentCandidates) {
            SourceCandidate normalizedCandidate = normalizeCandidateCanonicalUrl(candidate);
            if (normalizedCandidate != null) {
                merged.put(normalizedCandidate.getUrl(), normalizedCandidate);
            }
        }
        for (SourceCandidate candidate : updatedCandidates) {
            SourceCandidate normalizedCandidate = normalizeCandidateCanonicalUrl(candidate);
            if (normalizedCandidate != null) {
                merged.put(normalizedCandidate.getUrl(), sourceCandidateRanker.ensureScores(normalizedCandidate));
            }
        }
        return new ArrayList<>(merged.values());
    }

    private void appendAttemptedTargets(Map<String, SearchCollectionTarget> attemptedTargets,
                                        List<SearchCollectionTarget> newTargets) {
        if (newTargets == null) {
            return;
        }
        for (SearchCollectionTarget target : newTargets) {
            if (target == null || target.getCandidate() == null || !StringUtils.hasText(target.getCandidate().getUrl())) {
                continue;
            }
            SourceCandidate normalizedCandidate = normalizeCandidateCanonicalUrl(target.getCandidate());
            if (normalizedCandidate == null) {
                continue;
            }
            attemptedTargets.put(normalizedCandidate.getUrl(), target.toBuilder()
                    .candidate(normalizedCandidate)
                    .build());
        }
    }

    private List<SourceCandidate> removeExistingCandidates(List<SourceCandidate> supplementedCandidates,
                                                           List<SourceCandidate> existingCandidates) {
        Set<String> existingUrls = new LinkedHashSet<>();
        for (SourceCandidate candidate : existingCandidates) {
            SourceCandidate normalizedCandidate = normalizeCandidateCanonicalUrl(candidate);
            if (normalizedCandidate != null) {
                existingUrls.add(normalizedCandidate.getUrl());
            }
        }
        return supplementedCandidates.stream()
                .map(this::normalizeCandidateCanonicalUrl)
                .filter(candidate -> candidate != null && !existingUrls.contains(candidate.getUrl()))
                .toList();
    }

    /**
     * sitemap/robots 闂傚倸鍊搁崐鎼佸磹妞嬪海鐭嗗〒姘ｅ亾妤犵偛顦甸弫鎾绘偐閸愯弓鐢婚梻浣瑰濞叉牠宕愰幖浣稿瀭闁稿本绮嶉崰鎰版煟濡も偓閻楀棛绮閳ь剝顫夐幐椋庢濮樿泛钃熸繛鎴欏灩鍞梺鎸庣箓閹冲酣鈥栫€ｎ喗鈷戦柤濮愬€曢弸娆愩亜椤愩埄妲洪柟骞垮灩閳规垹鈧綆鍋掑Λ鍐ㄢ攽閻愭潙鐏ラ柛鐔稿閹便劌鈽夊鍡樺瘜闂侀潧鐗嗛崐鐟扳枍閹剧粯鐓曢悗锝庝簼椤ャ垺顨ラ悙宸█妤犵偞鐗楅幏鍛存偡妫颁胶缍嶉梻鍌欑婢瑰﹪宕戦崱娑樼獥閹艰揪绲介弸鍫⑩偓骞垮劚濡稓寮ч埀顒勬⒑閸愯尙娈遍柛瀣崌閺屾稑鈽夐崡鐐茬闂佸搫顑呴幊姗€骞冨Δ鍐╁枂闁告洦鍓涢ˇ銊╂⒑閸涘﹥鈷愭繛鑼枛瀹曟椽濮€閳╁啫鍔呴梺闈涱焾閸庢娊顢欓幒鎴富闁靛牆妫涙晶顒勬煟椤撗冩灍缂佹梻鍠栧鎾偄閾忓湱妲囬梻渚€娼ф蹇曞緤閸撗勫厹闁绘劦鍏欐禍婊堟煙鐎涙绠栭柟鍏煎姈閵囧嫰顢橀悙鏉戞灎婵犵鍓濋幃鍌炲春閿熺姴纾兼俊銈勭椤忓綊姊婚崒娆戭槮婵犫偓鏉堚晛鍨濇い鏍ㄧ矋閺嗘粓鏌ｉ弮鍥モ偓鈧柛瀣尭椤繈顢曢姀鐘点偖闂備礁鎼張顒勬儎椤栫偟宓侀悗锝庡櫘閺佸秵绻濇繝鍌涘珔缂佽京鏁哥槐鎾诲磼濞嗘劗銈版俊鐐存綑閹芥粓寮鈧幃銏ゆ惞闁稓鐟濇繝鐢靛仦閸ㄥ爼鏁冮埡渚囩劷闁冲搫鎳庣痪褔鏌涢顐簽闁告ɑ绋掔换娑氱箔閸濆嫬濮﹀┑顔硷功缁垶骞忛崨鏉戝窛濠电姴鍊瑰▓姗€姊哄Ч鍥х労闁割煈浜鏌ユ偐閼碱剚娈鹃梺纭呮彧缁犳垹绮堢€ｎ偁浜滈柡宥冨姀婢规﹢鏌熼婊冧粶妞ゎ亜鍟存俊鍫曞幢濡缚鏁块梻浣规偠閸斿秴顪冮挊澶屾殾闁哄洨鍎愰崥瀣熆鐠轰警鍎戦柛姗€浜跺娲传閸曨剙鍋嶉梺鍛婃煥閺堫剟寮查崼鏇炵骇婵炲棗澧介崬鐢告⒑閼姐倕鏋戝鐟版閹偤骞栨担鍦幐闁诲繒鍋涙晶钘壝洪幘顔界厱闁冲搫顑囩弧鈧悗瑙勬礃閿曘垺淇婇幖浣规櫆闂佹鍨版禍鐐亜閹惧崬鐏柍?     * 闂傚倸鍊搁崐椋庣矆娓氣偓楠炴牠顢曚綅閸ヮ剦鏁冮柨鏇楀亾闁汇倗鍋撶换婵囩節閸屾粌顤€闂佺顑戠换婵嬪蓟瀹ュ浼犻柛鏇ㄥ墮濞咃綁姊婚崒姘簽闁搞劋鍗抽垾鏃堝礃椤忎礁浜鹃柨婵嗙凹缁ㄥジ鏌熼惂鍝ユ偧闁汇儺浜獮鍡氼槹闁稿鍨介弻鈥崇暆鐎ｎ剛袦閻庢鍣崜鐔风暦瑜版帩鏁嬮柛娑卞枟椤旀垵鈹戦敍鍕杭闁稿﹥娲滈幑銏犖熼懡銈庢锤闂佸壊鍋呭ú鏍嫅閻斿吋鐓熼柡鍐ㄥ€哥敮鍓佺磼閻樺啿鍝洪柡宀嬬到铻栭柍褜鍓熼幃褍顭ㄩ崗鐐洴瀹曠喖顢涘☉妯圭敾婵犵數濮撮敃銈団偓姘煎幘濞嗐垽宕ｆ径宀€顔曢悗鐟板閸犳牠鐛弽銊ｄ簻闁哄浂浜炵粙鑽ょ磼閸屾稑绗ч柍褜鍓ㄧ紞鍡涘磻閸曨剛顩锋い鎾卞灪閳锋垿鎮峰▎蹇擃伌闁哥喎绻橀弻娑㈡偐瀹曞洤鈷岄梺缁樹緱閸犳牠顢樻總绋垮窛闁稿本绋掗ˉ鍫ユ煙椤旇娅婃俊顐㈠暙閳藉螖閳ь剟骞楃€ｎ偆绡€闁汇垽娼ф禒婊勩亜閿斿灝宓嗛柟顖氬椤㈡盯鎮欓弶鎴滅钵婵＄偑鍊栧ú宥夊磻閹炬惌娈介柣鎰儗閻掔晫绱掓潏銊ョ瑨閾伙綁鏌ｉ幘鎶筋€楃紒妤佹崌濮婅櫣鎷犻弻銉偓妤冪磼閻樿尙效鐎规洘娲樺蹇涘煛閸屾艾绨ラ梻浣告贡閸庛倕顫忔繝姘剹闁糕剝顨忛悢鍡涙煠閹间焦娑у┑顔肩墦閺岋綁骞樼€涙顦ㄧ紓浣虹帛閻╊垰鐣烽崡鐐嶇喖宕崟鍨秼闂傚倷娴囬褏鎹㈤崱娑樼柧婵犲﹤鐗勯埀顒€鍟存俊鐑藉煛閸屾埃鍋撻悜鑺ョ厱婵炲棗娴氬Σ鍝ョ磼閹邦厾銆掔紒杈ㄦ尰閹峰懏绂掔€ｎ亝鎳欓梺姹囧焺閸ㄧ晫鎹㈠┑瀣仒妞ゆ柨妲堥悢杞扮剨闁哄诞鍌氼棜闂備焦鐪归崹褰掑箟閳ユ枼鏋嶆繝濠傜墛閻撴洟鏌熸潏鍓у埌鐞氭氨绱撴担铏瑰笡缂佸鎹囬崺鈧い鎺戝€归弳鈺傘亜椤撶偟澧曢柣鈽嗗幘缁辨捇宕掑▎鎴М濡炪倧缂氶崡鎶界嵁閹邦喒鍋撻崷顓炐ｆい銉ｅ€濆缁樻媴閸涘﹥鍎撻柣鐐村嚬閸嬪﹤鐣烽弴銏犺摕闁靛鍎抽敍娑㈡⒑闂堟单鍫ュ疾濠婂牊鍋傞煫鍥ㄦ尨閺€浠嬫煟閹邦厼鐏ラ柛鐕佸亰瀵娊鎮㈤崗鑲╁幗闂侀潧鐗嗛崐鍛婄妤ｅ啯鈷掗柛灞捐壘閳ь剟顥撶划鍫熺瑹閳ь剟鐛弽顓ф晝闁挎棁妫勬禍閬嶆⒑缁洖澧查柧鏂款儔瀹曞爼顢楅埀顒傜不閵夛负浜滈柡鍐ㄦ搐琚氬┑鐐茬墔閸楀啿顫忛搹瑙勫枂闁告洦鍋勬慨銏ゆ⒑鐎癸附婢樻慨鍌溾偓瑙勬礃濞茬喖骞冮姀銈嗗殐闁宠桨绀佺徊濂告⒒娴ｈ鍋犻柛搴㈢矒瀹曠喖顢楅埀顒勬⒒椤栫偞鈷掑ù锝堟閸氬綊鏌涢悩鍙夘棦鐎规洝顫夌粋鎺斺偓锝庝海閹芥洖鈹戦悙鏉戠仸妞ゎ厼鍊块幃銏ゅ传閵壯勫殞婵＄偑鍊栭悧妤冨垝瀹€鍕畺闁硅揪闄勯埛鎺戙€掑锝呬壕濠电偠澹堝畷闈涱嚗婵犲啨鍋呴柛鎰╁妿閻ｆ椽姊虹粙璺ㄧ伇闁稿鐩幏鎴︽偄閻戞ê鏋戦梺鍝勫暙閻楀繐鐣垫笟鈧弻鐔告綇閸撗呮殸缂備胶濮电粙鎺楀Φ閸曨垰绫嶉柍褜鍓熷畷鏇㈠箮閽樺鐎梺鍓插亝濞叉﹢鍩涢幒鎳ㄥ綊鏁愰崶銊ユ畬濡炪倖娲樼划搴ｆ閹烘柡鍋撻敐搴′簻闁诲繑鎸抽弻鐔碱敍濮樿京鍔悗瑙勬礃鐢帡鍩ユ径濠庢建闁糕剝顨嗛鎾绘⒒閸屾瑧顦﹂柛鐔锋健楠炴牠顢曢敃鈧粻鐘诲箹鏉堝墽鎮奸柣顓炴閺屾稓浠﹂幆褜妫滈梺绋款儐閹瑰洭鎮伴鈧畷褰掝敊閻撳寒娼涢梻浣筋嚙鐎涒晠鎯岄鈧畷锟犲箮閽樺鐣烘繛鏉戝悑濞兼瑧绮婚懡銈囩＝濞达綀顕栭悞浠嬫煕濡湱鐭欐慨濠冩そ濡啫鈽夊杈╂澖闂備焦鎮堕崝宀€绱炴繝鍌ゅ殨闁归棿绀佺粻锝夋煟閹邦厽缍戠€殿喗瀵х换婵嬫偨闂堟刀銏ゆ煙閸愯尙绠绘い銏℃閹晝绱掑Ο鐓庡箥闂備浇宕甸崰鎰熆濮椻偓椤㈡棃顢橀悢缈犵盎闂侀潧楠忕槐鏇㈠煡婢跺浜滄い鎰剁悼缁犵偤鏌℃担鐟板鐎规洏鍔戦、妤呭焵椤掑媻澶婎潩椤撶姷鐦堥梺姹囧灲濞佳勭濠婂嫪绻嗘い鎰剁悼閹冲洦顨ラ悙鏉戝妤犵偞锕㈤、娆撴嚃閳哄骞㈤梻鍌欑濠€閬嶅磻閹炬剚鐒芥繛鍡樺灦椤愪粙鏌ｉ幇顔煎妺闁稿﹦鏁婚弻銊モ攽閸℃侗鈧顭胯閸ㄦ娊鍩€椤掑倸浠柛濠冪墪椤啴鎸婃径妯荤稁?     */
    private List<SourceCandidate> discoverCandidatesFromSitemaps(CollectorNodeConfig config,
                                                                 List<SourceCandidate> existingCandidates) {
        if (config == null || sitemapDiscoveryService == null || existingCandidates == null || existingCandidates.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> rootUrls = new LinkedHashSet<>();
        for (SourceCandidate candidate : existingCandidates) {
            if (!isTrustedSearchExpansionRoot(config, candidate)) {
                continue;
            }
            String rootUrl = toRootUrl(candidate == null ? null : candidate.getUrl());
            if (StringUtils.hasText(rootUrl)) {
                rootUrls.add(rootUrl);
            }
        }
        if (rootUrls.isEmpty()) {
            return List.of();
        }
        return removeExistingCandidates(
                sitemapDiscoveryService.discover(
                        config.getCompetitorName(),
                        safeSourceType(config.getSourceType()),
                        new ArrayList<>(rootUrls)
                ),
                existingCandidates
        );
    }

    /**
     * 闂傚倸鍊搁崐鎼佸磹妞嬪海鐭嗗〒姘ｅ亾妤犵偛顦甸弫鎾绘偐閸愯弓鐢绘俊鐐€栭悧妤冪矙閹炬眹鈧懘宕ｆ径宀€鐦堥梻鍌氱墛缁嬫帡鏁嶉弮鍫熺厾闁哄娉曟禒銏ゆ煏閸℃ê绗掓い顐ｇ箞閺佹劙宕ㄩ鈧ˉ姘舵⒑鐠囨彃顒㈡い鏃€鐗犲畷鏉款潩椤撶喐鐝峰┑掳鍊曢幊搴ｇ不濮樿埖鐓涢柛鎰╁妿婢ф盯鏌￠崨顔惧弨闁哄本绋撴禒锔剧磼閵忥紕鏆ュ┑鐐茬摠缁酣宕戦幘瀵糕攳濠电姴娴傞弫宥夋煟閹邦垱褰х紒閬嶆涧閳规垿鎮欓懠顒傚姼婵炲瓨绮犳禍顏勵嚕婵犳碍鏅插璺猴功閻も偓婵＄偑鍊栭幐楣冨磿閹邦儵娑㈠幢濞戞瑢鎷洪梺鍏肩ゴ閺呮粓宕甸埀顒佺節閳封偓閸涱喗鐝┑鈥冲级閸旀瑩鐛幒鎳虫棃鍩€椤掑倻灏电€广儱顦伴悡鏇㈡倶閻愭彃鈷旈柣鎿冨灣缁辨帡顢欓懖鈺佺厽濠殿喖锕ュ浠嬪蓟閸涱厸妲堟俊顖濇濞堝爼姊绘担瑙勫仩闁稿寒鍨跺畷婵堜沪鐟欙絾鐏佸┑鐘诧工閸犳艾銆掓繝姘厪闁割偅绻冮崳鐣岀磼閻橀潧顣肩紒缁樼☉椤斿繘顢欓悡搴ｇ潉闂備浇顕栭崳顖滄崲濠靛鏄ラ柨鐔哄Т瀹告繃銇勯弮鈧崕鎶界嵁濡ゅ懏鈷掑〒姘ｅ亾婵炰匠鍥ｂ偓锕傚醇閵夈儳锛熼梻渚囧墮缁嬩線寮崒娑栦簻闊洦鎸炬晶娑㈡煟閹惧鎳勯柕鍥у瀵噣宕惰濮规姊洪幐搴ｂ槈缂佸鐏氱粚杈ㄧ節閸パ呯厬婵犮垼娉涢敃銈夛綖閹烘挾绡€婵炲牆鐏濋弸鎾绘煕鐎ｎ偅宕屾慨濠呮缁辨帒顫滈崼婊呰繑闂備胶顭堥鍛偓姘煎幘缁顓兼径瀣偓閿嬨亜閹烘垵鈧顢欓弴銏♀拺缂侇垱娲栨晶鏌ユ嫅闁秵鍊堕煫鍥ь儏婵倿鏌＄仦鍓с€掗柍褜鍓ㄧ紞鍡涘磻閸涱垯鐒婇柟娈垮枓閸嬫捇宕归锝囧嚒闁诲孩鍑归崜鐔煎春閵夛箑绶炲┑鐘插閸嶅灚淇婇幓鎺撴拱闁绘顨呴弳鈺呮⒒閸屾艾鈧悂宕愭搴ｇ焼濞达綀娅ｇ粈濠傗攽閻樻彃浜為柣鎺旀櫕閹叉瓕绠涢弴鐐茬亰闂佸搫鍟悧鍡欑不缂佹ǜ浜滈柡鍐ㄥ€哥敮鍫曟煕鐎ｎ亞效婵﹥妞介幃鐑芥焽閿曗偓濞堣埖绻濆▓鍨灈闁诲繑宀稿畷姘節閸愵亪妾梺鍛婄☉閿曪箓宕㈤崡鐐╂斀闁绘绮☉褔鎮楀鐓庡⒋鐎规洘绻傞…銊╁醇閻斿搫骞堥梻浣稿暱閹碱偊宕愭繝姘ラ柟鐑樻⒒绾惧ジ寮堕崼娑樺閻忓繑澹嗙槐鎺懳旈崘銊︾亪閻庤娲橀崕濂杆囬懠顒傜＜闁绘灏欑粔鐑樻叏婵犲懏顏犻柟椋庡█閸ㄩ箖鎼归銈勬喚缂傚倸鍊风欢锟犲窗濡ゅ懎绠伴柟闂寸贰閺佸鏌ㄥ┑鍡橆棤妞も晝鍏橀幃妤呮晲鎼粹€茬盎婵炲濯崣鍐潖閾忚鍠嗛柛鏇㈡涧閺呴亶姊洪崫銉バｉ柣妤冨█閹即顢欑喊鍗炴倯闂佸憡渚楁禍婵嬪棘閳ь剟姊绘担鍝ユ瀮婵☆偄瀚灋婵°倕鎳忛崐鑸电箾閸℃ɑ灏伴柣鎾存礋閺岀喖骞嗚閸ょ喓绱掗悩铏鞍闁靛洤瀚伴、姗€鎮欓崗姝屾婵＄偑鍊ら崑鍛垝閹捐鏄ラ柍褜鍓氶妵鍕箳閹存繍浼€閻庤鎸烽懗鍫曞焵椤掆偓缁犲秹宕曢柆宓ュ洭顢涢悙鏉戜簵濠电偞鍨堕埣銈堛亹閹烘挻娅滈梺鎼炲劀閸愵煈鐎辩紓鍌氬€搁崐鍝ョ矓閹绢喗鍎楅柛灞惧嚬濞兼牗绻涘顔荤凹闁稿绻濋弻鐔封枔閸喗鐏嶉梺瑙勫絻閵堢顫忛搹鍦＜婵妫欓悾宄扳攽閻愭彃绾фい顓炴川閸?sitemap/robots 闂傚倸鍊搁崐鎼佸磹妞嬪海鐭嗗〒姘ｅ亾妤犵偛顦甸弫鎾绘偐閸愯弓鐢婚梻浣瑰濞叉牠宕愰幖浣稿瀭闁稿本绮嶉崰鎰版煟濡も偓閻楀棛绮閳ь剝顫夐幐椋庢濮樿泛钃熸繛鎴欏灩鍞銈嗘⒒閸樠囷綖閳哄懎绾?     * 闂傚倸鍊搁崐鎼佸磹妞嬪海鐭嗗〒姘ｅ亾妤犵偛顦甸弫鎾绘偐閸愯弓绨绘俊鐐€栫敮鎺楀磹缂佹鈻旂€广儱顦伴悡娆撳级閸繂鈷旈柣锝変憾閺屾盯濡搁妷銉㈠亾瑜版帒桅闁告洦鍨伴崘鈧梺闈浤涙担鎻掍壕闁圭儤顨嗛悡鍐磽娴ｈ偂鎴λ夐崼銉︾厸閻忕偟鏅暩濡炪伇鍌滅獢闁哄本鐩獮妯兼崉閻戞鈧顪冮妶鍡樼┛缂傚秳绶氶妴浣割潩鐠鸿櫣鍔﹀銈嗗笒鐎氼剟寮告笟鈧弻娑樼暆閳ь剟宕戦悙纰樺亾閻㈤潧孝妞ゎ厼娼″畷妤佸緞鐎ｎ偅绶梻浣告惈閹虫劖绻涢埀顒傗偓娈垮枛椤兘寮幇顓炵窞濠电姴鍊搁弫銈夋煟閻斿摜鐭婄紒澶婂濡叉劙鎮欑€涙ê顎撻梺鎯х箰濠€閬嶆晬濠婂牊鐓涘璺猴功婢ф垿鏌涢弬鑳闂囧銇勯弮鍫熸殰闁稿鎸搁埢鎾诲垂椤旂晫浜俊鐐€ら崣鍐绩鏉堛劎鈹嶅┑鐘叉处閸婂鏌ら幁鎺戝姕婵炲懏绮撳铏圭矙閹稿孩鎷遍梺娲诲弾閸犳绮╅悢鐓庡嵆闁绘棁娅ｉ鏇㈡煛婢跺﹦澧曞褏鏅划鏃堫敊婵劒绨婚梺闈涱檧闂勫嫮浜搁棃娑辩唵閻熸瑥瀚粈鍐磼鏉炴壆鐭欑€规洏鍔嶇换婵嬪礋椤愩垻顔囬梻鍌欐祰椤曆呮崲閹烘纾婚柣妯绘た閺佸鎲搁弬璺ㄦ殾婵犻潧妫悡銉╂煕椤愩倕鏋旈柛妯圭矙濮婃椽宕烽鈩冾€楅梺鎼炲妿婢ф寮查崼鏇炵煑濠㈣泛鐬奸鏇㈡⒑閻撳海绉洪柛瀣躬瀹曢潧鈻庨幘瀵稿帗闁荤姴娲﹂悡锟犲矗閸曨剦娈介柣鎰▕閸庢棃鏌℃担鐟板闁诡垱妫冩慨鈧柨婵嗘閻濓絾绻濋悽闈浶ラ柡浣规倐瀹曟垿鎮欓崫鍕€梺鍓插亝濞叉牜绮昏ぐ鎺撶厓闁告繂瀚崳褰掓煕鐏炶濮傞柡灞熷棛鐤€闁哄洨鍠庡▓宀勬⒑閹肩偛濡界紒璇插暟閹广垹鈽夐姀鐘茶€垮┑鈽嗗灥濞咃絾绂掓總鍛娾拺闁告稑顭▓鏇犵棯閺夎法肖闁瑰箍鍨归埥澶愬閻樿尪鈧灝鈹戞幊閸婃洟宕导鎼晩濠电姴瀚壕钘壝归敐鍛棌婵″弶鎮傞弻鏇㈠醇閻旂鈧劗鈧?root闂傚倸鍊搁崐鎼佸磹閻戣姤鍊块柨鏃堟暜閸嬫挾绮☉妯诲櫧闁活厽鐟╅弻鐔告綇妤ｅ啯顎嶉梺鎼炲€栭崝鏍Φ閸曨垰鍐€闁靛鍎卞В鍫濃攽閻愯尙姣為柡鍛█瀵寮撮悢铏圭槇闂婎偄娲﹀ú婊堝汲閻樺樊娓婚柕鍫濇缁€澶婎渻鐎涙ɑ鍊愭鐐茬墦婵℃悂濡锋惔锝呮灈鐎规洖缍婇、娆撳箚瑜嶇紓姘舵⒒閸屾瑧顦﹂柟纰卞亰楠炲﹥寰勯幇顒傛煣闂佺粯顭堥褏绮婚弽顐ｅ枑闁绘鐗嗙粭鎺楁煟閹邦剨韬柟顔款潐閵堬箓骞愭惔顔诲摋濠电偛顕刊顓㈠储閻ｅ本宕叉繝闈涱儏閻撴洟鏌嶈閸撶喎鐣峰鈧崺鈩冩媴闁垮鈻曢梻?/docs闂?pricing 缂傚倸鍊搁崐鎼佸磹閹间礁纾归柣鎴ｅГ閸婂潡鏌ㄩ弬鍨挃闁活厽鐟╅弻鐔封枎闄囬褍煤椤擃潿鈧礁顫濈捄铏瑰姦濡炪倖宸婚崑鎾绘煟閿濆洤鍘村┑锛勫厴閺佸啴鍩€椤掑倹顐介柣鎰劋閻撴瑩姊洪銊х暠濠⒀呭閵囧嫯鐔侀柛銉ｅ妷閹锋椽姊婚崒姘卞濞撴碍顨呭嵄闁割偅娲橀悡鏇㈠箹濞ｎ剙鈧捇宕ラ銈囩＜閺夊牃鏅涙禒杈殽閻愭惌鐒界紒杈ㄥ笒铻ｉ柣鎾抽婵?     */
    private boolean isTrustedSearchExpansionRoot(CollectorNodeConfig config, SourceCandidate candidate) {
        if (candidate == null) {
            return false;
        }
        return candidateOwnershipPolicy.isTrustedSearchRoot(
                config == null ? null : config.getCompetitorName(),
                config == null ? List.of() : defaultList(config.getCompetitorUrls()),
                candidate
        );
    }

    /**
     * Task 5 闂傚倸鍊搁崐宄懊归崶褏鏆﹂柣銏㈩焾缁愭鏌熼柇锕€鍔掓繛宸簻缁狅綁鏌ㄩ弮鍥棄闁逞屽墮濞硷繝寮婚妸鈺佸嵆闁绘劖绁撮崑鎾广亹閹烘垹锛熼梺鐟扮摠缁剁柉銇愰幒鎴狀槯闂佺绻楅崑鎰矙閸ヮ剚鈷戦柛锔诲幗閸も偓闂佺粯鐗曢妶绋款嚕婵犳艾鍗抽柣鏃囨椤旀洟姊洪崜鑼帥闁哥姵鐗楅幈銊﹀緞閹邦厸鎷虹紓浣割儐椤戞瑩宕曢幇鐗堢厵闁告稑锕ラ崐鎰版煕閳瑰灝鐏柟顖涙婵℃悂濡疯閸熷淇婇悙顏勨偓鏍偋濡ゅ懏鍎楁い鏃傛櫕閻濆爼鏌￠崶鈺佹灁缂佲檧鍋撻梻渚€娼ф蹇曞緤閸撗勫厹濡わ絽鍟悡銉╂煛閸ヮ煁顏堝礉閿旈敮鍋撶憴鍕闁告梹鐟︽穱濠囧醇閺囩偛鑰块梺鍐叉惈閸嬪棝宕澶嬧拻濞达綀娅ｇ敮娑㈡煕閺冣偓濞叉﹢寮查懜鍨劅闁靛鍎甸崬璺侯渻閵堝懐绠伴柣妤€锕幃锟犲礃椤忓懎鏋戝┑鐘诧工閻楀棛绮堥崒娑氱闁瑰鍋為惃鎴︽煟椤撶偞顥滈柕鍡樺笒椤繈鏁愰崨顒€顥氶梻鍌欐祰椤曟牠宕规导鏉戠柈闁哄鍨归弳锔界節婵犲倹锛嶆俊鎻掋偢閺岋絾鎯旈姀銏╂殹閻庡厜鍋撻柟闂寸閽冪喖鏌ㄩ悢鍝勑㈤柣鎰躬閺屽秵娼幍顔藉仹闂佹寧绻傚ú銊у婵傚憡鐓欓梺顓ㄧ畱瀵偓绻涢崼鐔虹煉闁哄矉绱曟禒锔惧寲閺囩偘澹曢梺褰掝暒缁€渚€骞冮幋锔解拺闁告稑锕ｇ欢閬嶆煕閻樻剚娈滄鐐插暙閳诲氦绠涢敐鍡楃槣闂備線娼ч悧鍡欐崲濡警鐎舵い鏇楀亾闁哄矉缍侀獮娆撳礋椤撶姷妲囨俊銈囧Х閸嬫盯宕婊呯焿闁圭儤鏌￠崑鎾绘晲鎼存ê浜炬い鎾寸⊕濞呭﹪鏌＄仦鐐鐎垫澘瀚埥澶婎潩鏉堛劍顔忓┑锛勫亼閸婃洖霉濮樿泛鍨傞柛顐ｆ礀閽冪喖鏌ㄥ┑鍡╂Ч闁稿﹦鍏橀幃妤呮偨閻㈢偣鈧﹥淇婇鐘插婵﹨娅ｇ划娆撳箰鎼粹剝鏁梺璇茬箰缁绘垿鎮烽埡鍛疇闁绘劗鍎ら悞鑲┾偓骞垮劚鐎涒晠寮查鍫熲拺闂侇偆鍋涢懟顖涙櫠椤曗偓閺岋綁鏁愰崶褍骞嬫繝娈垮枓閸嬫捇姊洪幐搴ｂ槈閻庢凹鍣ｉ幆渚€宕煎┑鍐╂杸濡炪倖姊归弸缁樼瑹濞戙垺鐓曢柟鐐綑閸濈儤銇勯姀鈩冾棃闁诡喓鍨藉畷顐﹀Ψ瑜滈崯搴ㄦ⒒娴ｇ儤鍤€妞ゆ洦鍙冨畷鎴︽倷閸忓摜鍓ㄥ銈嗘尵閸犲棙绂嶅鍫熺叆闁哄啫娴傞崵娆愮箾閸涱厾效闁哄矉绱曟禒锕傚磹閻斿浼冩俊鐐€ら崢褰掑礉閹存繄鏆﹀┑鍌氭啞閸嬪嫰鏌涘▎蹇ｆЦ闁幌佸洦鈷掗柛灞剧懆閸忓矂鏌涘Ο鐘叉搐缁犵喖鏌ㄩ悢鍝勵€岄柡浣革躬閺屸€愁吋鎼粹€崇闂佺厧鎽滈幊鎾绘箒闂佺粯锚濡﹪宕曢幇鐗堢厽闁规儳鐡ㄧ粈瀣煟閹垮啫浜扮€规洖鐖兼俊鎼佹晝閳ь剛鍠婂鍥╃＝闁稿本姘ㄥ皬缂備浇鍩栭懝楣冿綖韫囨洜纾兼俊顖濆亹椤旀洟姊虹紒妯哄Е闁告挻宀搁幃闈涱潩閼搁潧鈧敻鎮峰▎蹇擃仾缁剧偓鎮傞弻娑欐償閵堝棛褰х紓浣稿€圭敮锟犲极閸愵喖纾兼繛鎴炆戦悾浼存⒒娴ｇ鏆遍柟纰卞亰閺佸啴鏁愰崶顭戞綗?canonical URL闂?     * 闂傚倸鍊搁崐椋庣矆娓氣偓楠炴牠顢曚綅閸ヮ剦鏁冮柨鏇楀亾闁汇倗鍋撶换婵囩節閸屾粌顤€闂佺顑戠换婵嬪蓟瀹ュ浼犻柛鏇ㄥ墮濞咃綁姊婚崒姘簽闁搞劋鍗抽垾鏃堝礃椤忎礁浜鹃柨婵嗙凹缁ㄥジ鏌熼惂鍝ユ偧闁汇儺浜獮鍡氼槹闁稿鍨介弻鈥崇暆鐎ｎ剛袦閻庢鍣崜鐔风暦瑜版帩鏁嬮柛娑卞枟椤旀垵鈹戦敍鍕杭闁稿﹥娲滈幑銏犖熼懡銈庢锤闂佸壊鍋呭ú鏍嫅閻斿吋鐓熼柡鍐ㄥ€搁崝瀣偓瑙勬礀椤︾敻寮婚敐鍜佹僵妞ゆ挾鍠撶粙鍥ㄧ箾閺夋垵鎮戦柛鏃€鐟ラ～蹇撁洪鍛姷闂佺粯鍔樼亸顏嗏偓姘緲椤儻顧侀柛銊ョ埣瀵鏁愭径瀣簻闂佸憡鐟ラˇ浼存偟濮樿埖鈷戠紒顖涙礃閺夊綊鏌?URL 闂傚倸鍊搁崐鎼佸磹瀹勬噴褰掑炊椤掆偓杩濋梺閫炲苯澧撮柡灞剧〒閳ь剨缍嗛崑鍛暦瀹€鍕厸閻忕偠顕ч埀顒侇殘閸掓帒鈻庨幋鐐茬／闂侀潧顭堥崐妤併仚閾忣偆绡€闁汇垽娼ф禒婊堟煟濡も偓閿曨亜鐣峰┑瀣嵆闁靛繒濮烽崣鈧┑鐘灱閸╂牠宕濋弴鐘典笉濠电姵纰嶉崑鈩冪箾閸℃绠版い蹇旀綑闇夐柣姗嗗亝濞呭﹪鏌＄仦璇测偓婵嬬嵁閹邦厽鍎熼柨婵嗘濞呮岸姊绘担绛嬪殐闁哥姵顨婇妴鍐醇閵夈儳鐤呴梺鍦檸閸犳牜绮堢€ｎ偁浜滈柡鍐ㄥ€甸幏鈩冧繆椤愩倕鏋涙慨濠勭帛閹峰懘宕崟顐＄帛闂備浇顫夌粊鎾焵椤掍礁澧柣鏂挎閹便劌顪冪拠韫婵＄偑鍊戦崹鍝勭暆閹间礁鏋侀柟鐗堟緲闁卞洭鏌曡箛瀣仾婵犮垺鍨垮缁樼瑹閳ь剙顭囪閺佸秷绠涘☉妯汇仢婵炶揪绲芥晶锝夊Ψ閳哄倵鎷婚梺绋挎湰閼归箖鍩€椤掑嫷妫戞繛鍡愬灲閺佹捇鎮╅懠顒夋Ф婵犵數鍋涘Λ娆撳箰婵犳艾纾归柤鍝ユ暩缁♀偓闂傚倸鐗婃笟妤呭磿韫囨稒鐓曢悘鐐额嚙婵倿鏌＄仦鐐鐎规洜鍘ч埞鎴﹀箛椤撳绠撻弻锝夋偄閸濄儲鍤傜紓浣哄У閹瑰洭鐛崘顭戞建闁逞屽墴楠炲啫鈻庨幋鐐茬／闁哄鍋熸晶妤呮儓韫囨柧绻嗛柣鎰典簻閳ь剚娲滈幑銏ゅ箛椤掑倹娈惧銈嗗笒鐎氀囧焵椤掍焦顥堢€规洘锕㈤、娆撳床婢诡垰娲﹂悡鏇㈡煏婢舵稓鍒板┑陇濮ら幈銊╂晲閸涱垰顣洪梺瀹狀潐閸ㄥ潡骞冨▎鎾村€绘慨妤€鐗忛崥褰掓⒒娴ｅ憡鍟炴い銊ユ缁绘稒绻濋崶鈺佺ウ闂佺硶鍓濈粙鎴犵矆閸愨斂浜滈柡鍐ㄦ搐娴滆銇勯敂鑺ョ凡妞ゎ亜鍟存俊鍫曞川椤栨粠鍞舵繝纰樻閸嬪懘鎯勯鐐靛祦濠㈣泛绨烘禍褰掓煙閻戞ɑ灏ㄩ柟閿嬫そ濮婃椽宕ㄦ繝鍕ㄦ闂佹寧娲忛崝宥囩博閻斿娼ㄩ柍褜鍓熷濠氬Χ閸氥倛娅ｉ幏鐘绘嚑椤戝灝鎽嬮梻鍌欐缁鳖喚寰婃禒瀣簥闁肩厧澧庢禍娆撴⒒娴ｅ憡鍟為柛鏂跨箻瀵彃鈽夐姀鈺傛櫅濠电偞鍨堕敃鈺併€掓繝姘厪闁割偅绻冮ˉ鐐淬亜閵夈儲顥炲ǎ鍥э躬椤㈡洟濮€閻欌偓娴煎啴姊虹拠鈥虫灍闁挎岸鎽堕弽顓熺厱婵炴垵宕獮妯何旈悩鍙夊枠婵﹨娅ｉ埀顒€婀辨慨鐢稿Υ閸愵喗鍋ｅù锝呮贡閸欌偓閻庤娲橀崹鍧楃嵁濡偐纾兼俊顖濇〃缁ㄥ姊绘担铏瑰笡闁圭鎽滈埀顒佺▓閺呯娀骞冮崸妤€纾奸柣鎰ˉ閹风粯绻涙潏鍓у埌闁硅绻濆畷顖炴倷閻戞鍘介梺缁樻⒐濞兼瑩宕濋敂閿亾鐟欏嫭绀冮柛鏃€锕㈡俊鐢稿箛閺夎法顔婇梺鐟邦嚟閸嬬姷娆㈤姀銈嗏拻濞达綀娅ｇ敮娑欐叏婵犲偆鐓肩€规洏鍨奸ˇ瀛樼箾閹寸姵鏆柛鈹惧亾濡炪倖甯掔€氼參鍩涢幋鐘电＝濞达絽鍘滃Λ銊︺亜韫囨挾澧曢柣銈庡櫍閺岀喓绮欓崹顕呭妷闂佸憡鐟ョ换鎴﹀Φ閸曨垰绠抽柟瀛樼箥娴犻箖姊虹紒妯诲暗闁哥姵鐗犲璇差吋婢跺﹣绱堕梺鍛婃礉濞夋稒瀵奸幇鐗堚拺闁告繂瀚烽崕鎴︽煕閻樺磭澧电€殿喖顭烽弫鎰緞婵犲倸鏁ら梻浣圭湽閸ㄨ棄顭囪瀹曞搫鐣濋崟顒傚幐婵炶揪绲介幗婊勬櫠閿曞倹鐓涚€光偓鐎ｎ剙鍩屽銈庡亝缁挸鐣烽崡鐐嶆梹鎷呮潪鎵埍闂?     */
    private SourceCandidate normalizeCandidateCanonicalUrl(SourceCandidate candidate) {
        if (candidate == null || !StringUtils.hasText(candidate.getUrl())) {
            return null;
        }
        String canonicalUrl = canonicalUrlResolver.canonicalize(candidate.getUrl());
        if (!StringUtils.hasText(canonicalUrl)) {
            return null;
        }
        return candidate.toBuilder()
                .url(canonicalUrl)
                .domain(extractDomain(canonicalUrl))
                .build();
    }

    private List<SourceCandidate> concat(List<SourceCandidate> current, List<SourceCandidate> appended) {
        List<SourceCandidate> merged = new ArrayList<>(current);
        merged.addAll(appended);
        return merged;
    }

    private void appendSnapshotAndPublish(List<SearchProgressSnapshot> progressSnapshots,
                                          SearchExecutionPlan executionPlan,
                                          String currentStepCode,
                                          String message,
                                          boolean degraded,
                                          String degradationReason,
                                          Consumer<SearchExecutionUpdate> progressListener,
                                          List<SourceCandidate> sourceCandidates,
                                          List<SearchCollectionTarget> selectedTargets,
                                          SearchExecutionTrace executionTrace) {
        progressSnapshots.add(buildProgressSnapshot(executionPlan, currentStepCode, message, degraded, degradationReason));
        publishProgress(progressListener, executionPlan, progressSnapshots, sourceCandidates, selectedTargets, executionTrace);
    }

    private void publishProgress(Consumer<SearchExecutionUpdate> progressListener,
                                 SearchExecutionPlan executionPlan,
                                 List<SearchProgressSnapshot> progressSnapshots,
                                 List<SourceCandidate> sourceCandidates,
                                 List<SearchCollectionTarget> selectedTargets,
                                 SearchExecutionTrace executionTrace) {
        if (progressListener == null) {
            return;
        }
        List<SearchProgressSnapshot> snapshotHistory = progressSnapshots == null ? List.of() : new ArrayList<>(progressSnapshots);
        SearchProgressSnapshot latest = snapshotHistory.isEmpty() ? null : snapshotHistory.get(snapshotHistory.size() - 1);
        progressListener.accept(SearchExecutionUpdate.builder()
                .executionPlan(executionPlan)
                .latestProgress(latest)
                .progressSnapshots(snapshotHistory)
                .sourceCandidates(sourceCandidates == null ? List.of() : new ArrayList<>(sourceCandidates))
                .selectedTargets(selectedTargets == null ? List.of() : new ArrayList<>(selectedTargets))
                .executionTrace(executionTrace)
                .build());
    }

    private void markStepRunning(SearchExecutionPlan executionPlan, String stepCode, String message) {
        updateStep(executionPlan, stepCode, SearchExecutionStep.StepStatus.RUNNING, message, true);
    }

    private void markStepSuccess(SearchExecutionPlan executionPlan, String stepCode, String message) {
        updateStep(executionPlan, stepCode, SearchExecutionStep.StepStatus.SUCCESS, message, false);
    }

    private void markStepSkipped(SearchExecutionPlan executionPlan, String stepCode, String message) {
        updateStep(executionPlan, stepCode, SearchExecutionStep.StepStatus.SKIPPED, message, false);
    }

    private void updateStep(SearchExecutionPlan executionPlan,
                            String stepCode,
                            SearchExecutionStep.StepStatus status,
                            String message,
                            boolean markStarted) {
        if (executionPlan == null || executionPlan.getSteps() == null) {
            return;
        }
        for (int index = 0; index < executionPlan.getSteps().size(); index++) {
            SearchExecutionStep step = executionPlan.getSteps().get(index);
            if (!stepCode.equals(step.getStepCode())) {
                continue;
            }
            executionPlan.getSteps().set(index, step.toBuilder()
                    .status(status)
                    .message(message)
                    .startedAt(markStarted && step.getStartedAt() == null ? LocalDateTime.now() : step.getStartedAt())
                    .completedAt(status == SearchExecutionStep.StepStatus.SUCCESS
                            || status == SearchExecutionStep.StepStatus.FAILED
                            || status == SearchExecutionStep.StepStatus.SKIPPED ? LocalDateTime.now() : null)
                    .build());
            return;
        }
    }

    private SearchProgressSnapshot buildProgressSnapshot(SearchExecutionPlan executionPlan,
                                                         String currentStepCode,
                                                         String message,
                                                         boolean degraded,
                                                         String degradationReason) {
        List<SearchExecutionStep> steps = executionPlan == null || executionPlan.getSteps() == null
                ? List.of()
                : executionPlan.getSteps();
        int totalSteps = steps.size();
        int completedSteps = (int) steps.stream()
                .filter(step -> step.getStatus() == SearchExecutionStep.StepStatus.SUCCESS
                        || step.getStatus() == SearchExecutionStep.StepStatus.FAILED
                        || step.getStatus() == SearchExecutionStep.StepStatus.SKIPPED)
                .count();
        int progressPercent = totalSteps == 0 ? 0 : (int) Math.round((completedSteps * 100.0D) / totalSteps);

        String currentStep = steps.stream()
                .filter(step -> currentStepCode.equals(step.getStepCode()))
                .map(step -> StringUtils.hasText(step.getGoal()) ? step.getGoal() : step.getStepCode())
                .findFirst()
                .orElse(currentStepCode);

        String status;
        if (steps.stream().anyMatch(step -> step.getStatus() == SearchExecutionStep.StepStatus.FAILED)) {
            status = "FAILED";
        } else if (degraded) {
            status = "DEGRADED";
        } else if (completedSteps >= totalSteps && totalSteps > 0) {
            status = "SUCCESS";
        } else {
            status = "RUNNING";
        }

        return SearchProgressSnapshot.builder()
                .currentStep(currentStep)
                .currentStepCode(currentStepCode)
                .completedSteps(completedSteps)
                .totalSteps(totalSteps)
                .progressPercent(progressPercent)
                .status(status)
                .message(message)
                .degraded(degraded)
                .degradationReason(degradationReason)
                .updatedAt(LocalDateTime.now())
                .build();
    }

    /**
     * 闂傚倸鍊搁崐鎼佸磹閻戣姤鍤勯柛顐ｆ磸閳ь兛鐒︾换婵嬪磼濡や胶浜欐繝鐢靛仦閸垶宕瑰ú顏勭柧婵犻潧顑嗛悡蹇擃熆鐠虹儤顥炴繛鍛噽缁辨帡鎮╁畷鍥р拡闂侀€炲苯澧叉い顐㈩槸鐓ら柡宥庣亹濞差亝鏅濋柛宀嬪缁嬪繘姊洪崫鍕偍闁搞劍妞介幃陇绠涘☉姘絼闂佹悶鍎崕閬嶅礉閵堝鐓欐い鏂跨仢娴滃湱绱掓潏銊ユ诞闁诡喒鏅涢蹇涱敊閹勫€┑掳鍊楁慨鐑藉磻閻愮儤鍋嬫俊銈傚亾妞ゎ厼娲╃粻娑樷槈濡⒈妲堕柣鐔哥矊缁绘ê顕ｉ幎鑺ュ亹缂備焦锚閳ь剛鏁婚弻娑㈡晜鐠囨彃瀛ｉ梺缁樺笧閸嬫捇濡甸崟顖ｆ晣闁绘劕寮朵簺闂備椒绱徊鍧楀礂濮椻偓瀵偊骞囬鐐电獮婵犵數濮寸€氼噣宕㈤鈧埞鎴︽偐椤旇偐浼囧┑鐐差槹閻╊垶鍨鹃敃鍌ゆ晢闁逞屽墴閳ワ箓宕堕鈧粻鑽ょ磽娴ｅ顏呯椤撶偐鏀介柣妯款嚋瀹搞儵鎮楀鐓庡⒋妞ゃ垺鎸搁…銊╁礃閻愵剙鐦滈梺璇插缁嬫帟鎽梺绋匡攻閸旀鍩€椤掑喚娼愭繛鍙夌矒楠炲﹪骞樻导娆戠◤濠电娀娼уú锔剧礊閸ヮ剚鐓冮柛婵嗗閺嗙喖鏌涘鍡楃仸婵﹦绮幏鍛村川婵犲倹娈橀梻浣告啞濮婂綊鈥﹂崶銊ь洸缂佸绨遍弸搴ㄦ煙閹规劖鐝弶?replay 闂傚倸鍊搁崐鎼佸磹妞嬪海鐭嗗〒姘ｅ亾妤犵偞鐗犻、鏇㈡晝閳ь剟鎮块濮愪簻闁规澘鐖煎顕€鏌涚€ｎ亶妯€闁哄矉缍侀獮姗€宕樺顔兼暔婵犵數鍋為崹鍓佸枈瀹ュ懐鏆﹀鑸靛姈閻撳繐鈹戦悙鎴濆暙椤忕數绱掗幉瀣瘈婵﹥妞介幊锟犲Χ閸涱喚鈧椽姊婚崒姘仼缂佸鎳撻悾鐑藉箛閺夎法鐤€闂佸搫顦冲▔鏇㈡晬?     * 闂傚倸鍊搁崐鎼佸磹閻戣姤鍤勯柛顐ｆ磵閳ь剨绠撳畷濂稿Ψ閿旇姤鐝栭梻渚€娼чˇ顐﹀疾濠婂牆纾婚柨鐔哄У閻撳啴鏌涘┑鍡楊仾闁革絽缍婇弻锝夊箳閹寸姳绮甸梺闈涙搐鐎氫即鐛幒妤€骞㈡俊鐐村劤椤ユ艾鈹戦敍鍕杭闁稿ě鍛亾闂堟稓鐒哥€规洩缍佸畷姗€顢欓崗鍏夹氶梻渚€鈧偛鑻晶顖炴煏閸パ冾伃妤犵偛顑夐弫鎰板幢濞嗗秮鍋撴繝鍌ゆ富闁靛牆绻楅铏圭磼閻樿櫕宕岀€殿喖顭烽弫鎰緞婵炩懇鏅犻弻鏇熷緞閸繂濮夐梺鍦焾閸熸潙顫忔ウ瑁や汗闁圭儤绻冮ˉ鏍ㄧ節閻㈤潧浜归柛瀣崌閹鈻撻崹顔界亶濠电偛鍚嬮悷銊╂倶閹烘鍊甸柣鐔告緲椤忣厽銇勯敐蹇涙缂佹梻鍠栧鎾偄閾忚鍟庨梻浣告贡閸嬫挸顭囧▎鎾村€跨憸鐗堝笚閻撴洟鎮楅敐搴′簼鐎规洖鐭傞弻锛勪沪閻ｅ睗銉︺亜瑜岀欢姘跺蓟濞戙垹绠婚悗闈涙啞閸ｄ即鏌﹀Ο鑽ょ畺闁靛洤瀚板鏉懳旈埀顒佺妤ｅ啯鈷戠痪顓炴噺閻濐亪鏌ｉ悢鍙夋珚闁绘侗鍠氶埀顒婄秵閸犳寮插┑瀣厓鐟滄粓宕滃┑鍫熷床婵﹩鍏橀弨浠嬫煟閹邦垰鐓愮憸鎶婂懐纾界€广儱瀚粣鏃傗偓娈垮枔閸斿秴顭囪箛娑樜╃憸澶愬几閺嶎厽鈷戦梻鍫熺〒缁犳岸鏌熼崘鍙夊枠鐎规洘鍨块獮姗€寮妷锔句簴闂備礁澹婇悡鍫ュ窗濡ゅ懏鍊堕柛顐犲劜閳锋垿鏌涘┑鍕姎婵炲懎绻橀弻娑㈠Ω閵婏妇銆愬銈嗘穿缂嶄線鐛惔銊﹀殟闁靛鍎扮花濠氭⒒娴ｄ警鐒剧紒缁橆殜瀹曟垿骞囬弶鍨亶闂佸湱鍎ら〃鍡浰夋繝鍐︿簻闁规壋鏅涢悘顏勵熆鐠哄搫顏柟渚垮妽缁绘繈宕掑鍛呫劌鈹戦纭烽練婵☆偄瀚伴、姗€宕楅悡搴ｇ獮闁诲函缍嗛崜娆撶嵁濡　鏀介柣妯肩帛濞懷囨煕婵犲啯鍊愰挊婵囥亜閺冣偓瀹曟ɑ鎱ㄩ幎鑺ョ厱闊洦鑹炬禍鍦磼閻樿崵鐣洪柡宀€鍠栭獮鍡氼槻闁哄棜椴搁妵鍕晲閸涱垽绱炵紓浣介哺鐢顭囪箛娑樜╅柕澶涚畳閻т線鏌ｆ惔銈庢綈婵炲弶绮撳畷銏ｎ樄闁炽儻绠撴俊鎼佸煛娴ｅ摜鐛╂俊鐐€栭悧妤佺瑹濡ゅ懎瑙﹂柛銉墯閳锋帒霉閿濆洨鎽傞柛銈嗙懃铻栭柣妯活問閻掗箖鏌ㄩ弴妯虹伈闁哄苯妫楅濂稿川椤栥倗骞㈤梻鍌欒兌缁垶宕濋幒鏂跨筏缂備焦蓱閸欏繘鏌ц箛锝呬簴濞存粍绮撻弻锟犲炊椤垶鐣舵繛瀛樼矒缁犳牕顫忛悜妯诲闁规鍣Σ顔剧磽娴ｅ壊妲奸柛鈺傜墱缁骞掑Δ浣规珖闂佺鏈粙鎴濃枔閹扮増鈷戦柛鎰级閹牓鏌涢悩鍐插摵鐎规洦鍨伴鍏煎緞鐎Ｑ勫闂備胶鍘ч～鏇㈠磹閺囩偟鎽ュ┑鐘垫暩閸嬬娀顢氬鍛筏閻犳亽鍔岄崹婵嬫煕椤愩倕鏋戝┑顖涙尦閹綊宕堕妷銉ュ濠碉紕鍋犲Λ鍕敋閿濆棛顩烽悗锝呯仛閺咃綁姊虹紒妯哄婵炰匠鍥х閻庯綆鍠楅埛鎺懨归敐鍫燁仩閻㈩垱鐩弻娑㈠籍閹惧墎鏆ら悗瑙勬礃缁矂锝炲┑鍫㈠崥妞ゆ牗纰嶇粚鎸庛亜閿旇棄鈻曢柡宀€鍠愮粭鐔煎垂椤旂⒈娼庨梻浣芥〃閻掞箓宕濆▎蹇曟殾闁靛ň鏅╅弫宥嗙節婵犲倿顎楅柟顔肩墦濮婂宕掑顑藉亾妞嬪海鐭嗗ù锝呭閸ゆ洟鏌涢幘鑼槮闁搞劍绻勯埀顒€鍘滈崑鎾绘煕閺囩偛顣崇紒瀣箻濮婃椽妫冨☉姘辩杽闂佺锕ュú鏍极椤曗偓濮婄粯鎷呯粙鎸庢瘣闂佸湱鈷堥崑澶嬫櫠濠靛鈷戠紒瀣儥閸庢劙鏌熼悷鐗堟悙闁伙絽鍢查～婊堝焵椤掑嫨鈧礁鈻庨幘鏉戜患闁诲繒鍋犲Λ鍕搹闂傚倸鍊搁崐鐑芥嚄閸洍鈧箓宕奸妷锔芥珖闂佹寧娲栭崐鍝ョ玻濡ゅ懎绠规繛锝庡墮婵″ジ鏌涚仦璇插闁哄瞼鍠撶槐鎺楀閻樺磭浜堕梻浣侯焾椤戝倿宕滃┑鍫熷床婵炴垯鍨归獮銏′繆椤栨艾鎮戦柛锝庡枤缁辨挻鎷呴崜鍙夆枔闂侀潻缍囩紞浣割嚕鐠囨祴妲堥柕蹇婂墲濞呮粓姊洪幖鐐插姤闁糕晜鐗犺棢闁瑰墽绮埛鎴︽⒒閸喓鈯曞璺哄閺屾盯寮埀顒勬偡閳哄懏鍋樻い鏃傗拡濞笺劑鏌嶈閸撴瑩鎮惧畡鎷旂喐绗熼姘珚婵＄偑鍊栧濠氬疾椤愶箑鐒垫い鎺戭槸娴滅増鎱ㄦ繝鍕笡闁瑰嘲鎳橀幃婊兾熼悜妯兼殮闂傚倷绀侀幉锟犳嚌妤ｅ喚鏁勯柛銉墮缁€鍡涙煙閻戞ɑ灏伴柛搴ｅ枛閺屾洘绻涢崹顔煎Б闂佹寧绋掔划鎾愁潖濞差亝顥堟繛娣劚閻楁挸顕ｉ幓鎺濈叆闁割偅绻勯敍娑樷攽閻樼粯娑фい鎴濇閹€斥槈濮楀棛鍞甸柣鐘烘〃鐠€锕傚磿韫囨柣浜滈柡鍥╁枔閻瞼绱掓潏銊ユ诞濠碘剝鎮傞弫鍐焵椤掑嫬浼犳繛宸簼閻撴瑦銇勯弮鍌氬付婵℃彃顭烽弻宥囨嫚閸欏鏀紓浣哄У閻╊垶鐛▎鎾崇鐟滃繐螞椤栫偞鈷掑ù锝呮惈鐢爼鏌ｈ箛鎾跺ⅵ鐎殿喗鐓￠幃鈺冩嫚閼艰埖鎲?     */
    private List<SearchReplayTimelineItem> buildReplayTimeline(List<SearchProgressSnapshot> progressSnapshots,
                                                               List<SourceCandidate> sourceCandidates,
                                                               List<SearchCollectionTarget> attemptedTargets,
                                                               List<SearchCollectionTarget> selectedTargets,
                                                               List<SourceCandidate> discardedCandidates,
                                                               List<String> sourceUrls) {
        if (progressSnapshots == null || progressSnapshots.isEmpty()) {
            return List.of();
        }
        int candidateCount = sourceCandidates == null ? 0 : sourceCandidates.size();
        int attemptedCount = attemptedTargets == null ? 0 : attemptedTargets.size();
        int selectedCount = selectedTargets == null ? 0 : selectedTargets.size();
        int discardedCount = discardedCandidates == null ? 0 : discardedCandidates.size();
        List<String> stableSourceUrls = sourceUrls == null ? List.of() : sourceUrls;
        return progressSnapshots.stream()
                .filter(snapshot -> snapshot != null && StringUtils.hasText(snapshot.getCurrentStepCode()))
                .map(snapshot -> SearchReplayTimelineItem.builder()
                        .stepCode(snapshot.getCurrentStepCode())
                        .stepName(snapshot.getCurrentStep())
                        .status(snapshot.getStatus())
                        .message(snapshot.getMessage())
                        .completedSteps(snapshot.getCompletedSteps())
                        .totalSteps(snapshot.getTotalSteps())
                        .progressPercent(snapshot.getProgressPercent())
                        .candidateCount(candidateCount)
                        .attemptedCount(attemptedCount)
                        .selectedCount(selectedCount)
                        .discardedCount(discardedCount)
                        .degraded(snapshot.getDegraded())
                        .degradationReason(snapshot.getDegradationReason())
                        .sourceUrls(stableSourceUrls)
                        .updatedAt(snapshot.getUpdatedAt())
                        .build())
                .toList();
    }

    /**
     * Tavily 闂傚倸鍊搁崐鎼佸磹閹间讲鈧箓顢楅崟顐わ紱闂佸憡娲﹂崐瀣亹閹烘垹锛滈梺缁樺姌鐏忔瑩鏁嶅☉娆戠瘈闁汇垽娼у瓭濠电偛鐪伴崐婵嬪箖閻愬搫鍨傛い鎰С缁ㄥ姊洪崷顓炲妺闁搞劎鏁婚崺鈧い鎺嶇劍閸婃劗鈧鍠氶…鍫ュ煡婢舵劕顫呴柨娑樺鐢鏌ｉ悢鍝ョ煁缂侇喗鎸搁悾宄邦煥閸愮偓鍍靛銈嗗笒閸婄懓鈻撻弴銏♀拺闁告稑锕﹂埥澶愭煥閺囶亞鐣甸柕鍡楁嚇楠炴捇骞戦妸锔筋棃闁轰焦鍔欏畷銊╊敇閻斿壊鍞归梻鍌欒兌椤牊顨ラ崫銉х煋鐟滅増甯掗拑鐔哥箾閹存瑥鐏╅柛妤佸▕閺屾洘绻涢崹顔煎闂佺厧澹婃禍顏勵潖濞差亜宸濆┑鐘插€婚崝浼存⒑缁嬫鍎愰柟鐟版搐椤繐煤椤忓懎娈ラ梺闈涚墕閹叉盯鏁嶉崟顓狅紲濡炪倖鍔戦崹缁樻櫏濠电姷顣介埀顒€纾崺锝団偓瑙勬礈閸犳牠銆佸鈧崺妤呭煛閸屾稒姣岄梻鍌氬€搁崐鎼佸磹妞嬪海鐭嗗〒姘ｅ亾妤犵偛顦甸弫鎾绘偐閸愬弶鐤勫┑掳鍊х徊浠嬪疮椤栫偛纾婚悗锝庝簴閺€浠嬫煟濡搫绾ч柟鍏煎姈娣囧﹪顢曢敍锝庝邯婵℃挳骞掗幋顓熷兊闂佹寧绻傞幊宥嗙珶閺囥垺鐓?coordinator 闂傚倸鍊搁崐鎼佸磹妞嬪海鐭嗗〒姘ｅ亾妤犵偞鐗犻、鏇㈡晜閽樺缃曟繝鐢靛Т閿曘倝鎮ч崱娆忣棜濠电姵纰嶉悡鏇㈡煃閳轰礁鏆曠紒鍫曚憾閺屸剝鎷呯粙搴撳亾瑜版帒桅闁告洦鍨扮粻鎶芥煙鐎涙濡囬柛瀣崌閹筹繝濡堕崪浣诡棥濠电娀娼ч崐鎼佸箟閿熺姵鍋傞柣鏂垮悑閻撴盯鏌涚仦鐐殤濞寸姰鍨洪妵鍕晲閸喓褰ч梺闈涙搐鐎氼垳绮诲☉妯锋婵°倓绀侀弸銈夋⒒娴ｇ瓔鍤冮柛鐘愁殜閵嗗啯绻濋崶褏鐣洪梺缁樺灱婵倝宕戠€ｎ喗鐓曟い鎰╁€曢弸鎴︽倵濮橆厼鈻曟慨濠勭帛閹峰懐鎲撮崟顐″摋闂備礁鎲￠弻銊х矓閹绢喗鍋╂繝闈涱儏缁犵懓霉閿濆懏鍟為柣?     * 闂傚倸鍊搁崐椋庣矆娓氣偓楠炴牠顢曚綅閸ヮ剦鏁冮柨鏇楀亾闁汇倗鍋撶换婵囩節閸屾粌顤€闂佺顑戠换婵嬪蓟閵娾晛鍗抽柣鎰ゴ閸嬫捁銇愰幒鎴狅紱闁诲函缍嗛崰妤呭煕閹寸偑浜滈柟鍝勬娴滃墽绱撴担鍓叉Ч闁瑰憡濞婇獮鍡欎沪鏉炴寧姊归幏鍛村礂閸濄儳娉块梻鍌欑閹碱偆绮旈弻銉ョ閹兼番鍔岀粻鐘裁归敐鍫燁仩缁炬儳銈搁弻鏇熺箾閸喒鍋撻弴鐐垫殼闁糕剝绋掗悡娑氣偓鍏夊亾閻庯綆鍓涜ⅵ闂備胶纭堕弬渚€宕滃┑瀣闁告稑鐡ㄩ崐缁樹繆椤栨繍鍤欓柡瀣洴濮婄粯鎷呴崷顓熻弴闂佺硶鏅涚€氭澘鐣风憴鍕嚤閻庢稒锚娴犻亶姊洪棃娑辩劸闁稿孩妞藉畷闈浳旈崨顔惧幈闂佸搫娲㈤崝灞剧娴犲鐓曟慨姗嗗墻閸庢棃鏌＄仦鍓ф创濠碉紕鍏橀弫鎰板川椤撗呪偓瀛樼節閻㈤潧浠滈柣妤€瀚板畷褰掓寠婢舵ɑ缍庡┑鐐叉▕娴滄粓骞戦懜鐐逛簻闁规崘娉涙禒婊冣攽閳╁啯銇濋柡宀嬬秮婵偓闁宠桨鑳舵禒顓㈡煟閵忊晛鐏ｉ柛瀣ㄥ€濋獮濠囨倷閻戞鍔堕悗骞垮劚鐎涒晠寮查敐澶嬧拺闂傚牊绋撶粻鍐测攽椤旇偐澧﹀┑锛勫厴婵＄兘宕橀妸褏楔闂佽桨鐒﹂崝娆忕暦閵娾晩鏁囩憸搴ㄦ晬娴ｇ硶鏀介柣姗嗗枛閻忚鲸绻涙径瀣灱濠⒀勭箞濮婅櫣鎮伴垾鍏呭闂備焦瀵уú鏍磹瑜版帗鍋傛繛鍡樻尰閻撴瑥霉閿濆懎鏆為柣婵愪邯閺屸剝寰勬繝鍕ㄩ梺鍝勬湰缁嬫捇鍩€椤掑﹦绉甸柛瀣噽娴滃憡瀵肩€涙鍘甸悗鐟板婢ф宕甸崶顒佺厵闁绘挸瀛╃拹锟犳煙閸欏灏︾€规洜鍠栭、妤呭焵椤掆偓閳诲秴顓兼径瀣ф嫽闂佺鏈懝楣冨焵椤掑嫷妫戞繛鍡愬灲閺佹捇鎮╅懠顒婄幢闂備礁鎲″ú锕傚垂娓氣偓瀹曞爼顢楁径瀣珦闂備礁鎲￠幐鍡涘磼濠婂懏鍠掗梻鍌氬€烽悞锔锯偓绗涘懏宕查柛宀€鍊涢崶顒夋晬婵犲﹤瀚弸鍌炴⒑閹稿孩绀€闁稿﹤鎽滅划濠氬捶椤撶姷锛滃銈嗘⒒閸樠呮暜閵娾晜鐓曢悗锝庡亝瀹曞瞼鈧娲橀敃銏犵暦閿濆棗绶炴俊顖滃劋閸婎垳绱撻崒姘偓鎼佸磹閹间礁纾归柟闂寸绾剧懓顪冪€ｎ亝鎹ｉ柣顓炴闇夐柨婵嗘搐閸斿鈧娲橀悡锟犲蓟閻斿吋鍊绘俊顖濆吹椤︺儳绱撻崒姘毙㈤柛濠傜仢椤繘鎼圭憴鍕彴闂佸憡鐟ラˇ顖炲绩椤撶儐娓婚柕鍫濋娴滄粍銇勯敂鐐毈妤犵偛妫楅悾婵嬪礋閸偅娅撻梻浣告啞閹稿棝宕ㄩ娑欘啍闂傚倸鍊风粈渚€骞夐敍鍕煓闁圭儤鍩堥悞鑺ョ箾閸℃ê鐏╂俊顐灣閹叉悂寮崼婵婃憰闂佸搫娲ㄩ崰鎾绘偟閼哥偣浜滈柡宥冨姀婢规ɑ銇勮箛锝呬喊闁哄矉绻濆畷鍗炩枎韫囧﹥鐎版繝娈垮枛閿曘劌鈻嶉敐鍥у灊婵炲棙鎸哥粈宀勬煃閳轰礁鏆為柡鍡曞嵆濮婄粯鎷呴挊澶婃優闂佸摜鍠庡鈥愁嚕閺屻儲鍋愰悹鍥у级濡差剟姊洪柅鐐茶嫰婢ь垶鏌曢崶褍顏鐐村浮瀹曞崬顪冮幆褜妫滄繝纰夌磿閸嬫垿宕愯缁骞橀幇浣哄姺閻熸粍妫冨畷娲閵堝懐鐫勯梺鍓插亞閸犳劕鈻嶉崶顒佲拺缂佸瀵у﹢鏉壳庨崶顒傜窗妞ゃ倕鍊垮濠氬磼濞嗘埈妲繝銏㈡嚀閿曨亜鐣烽幋锕€绠虫俊銈傚亾缂佲偓閸喓绠鹃柟瀵稿仦閻ㄦ垿鏌ｉ鐐搭棞妞ゎ厼娼￠幊婊堟濞戞﹩娼旈梻浣瑰▕閺€閬嶅垂閸洖桅闁告洦鍨扮粻鎶芥煕閳╁啨浠﹀瑙勬礋濮婄粯鎷呯粵瀣缂備胶绮崹褰掑箲閵忋倕閱囨繝闈涚墛濞堥箖姊洪棃娑氱疄闁稿﹥娲栧ú鍨攽閻橆喖鐏辨繛澶嬬洴閺佸啴鏁冮崒銈嗘櫓?provider 闂傚倸鍊搁崐鎼佸磹閹间礁纾归柣鎴ｅГ閸ゅ嫰鏌涢锝嗙缁炬儳顭烽弻鐔煎箚瑜忛敍宥夋煛閸☆參妾柟渚垮妼椤啰鎷犻煫顓烆棜闂傚倷娴囬鏍垂娴兼潙瀚夋い鎺戝鍥撮梺鎸庣箓椤︻垳绮堥崘顔界厪濠电倯鍐ㄦ殲闁瑰眰鍨藉濠氬磼濞嗘劗銈伴悗瑙勬礈閺佽鐣锋导鏉戠疀妞ゆ挾濮疯ぐ楣冩⒑缁洖澧查柣鐕傜畱閻ｇ兘骞撻幒瀣у亾閹烘埈娼╅柨婵嗘噸婢规洟鏌ｆ惔銏╁晱闁哥姵鐗犻幃銉︾附缁嬫寧妲梺鏂ユ櫅閸燁垱鍒婇幘顔界厽闁绘梻鍘ф禍鐗堜繆閼兼湹鍚紒杈ㄦ崌瀹曟帒鈻庨幇顔哄仒婵＄偑鍊栭弻銊╂晝閿曗偓閳诲酣濮€閻欌偓濞尖晜銇勯幘瀵哥畼缂?     */
    private TavilyFastLaneAudit buildTavilyFastLaneAudit(List<SourceCandidate> sourceCandidates,
                                                         List<SearchCollectionTarget> selectedTargets,
                                                         boolean providerFallbackUsed,
                                                         TavilyFastLaneAudit providerAudit) {
        TavilyFastLaneAudit candidateAudit = buildCandidateTavilyFastLaneAudit(
                sourceCandidates,
                selectedTargets,
                providerFallbackUsed
        );
        if (providerAudit != null
                && providerAudit.getFieldEvidenceQueryExecutions() != null
                && !providerAudit.getFieldEvidenceQueryExecutions().isEmpty()) {
            return providerAudit.toBuilder()
                    .playwrightInvocationBaselineHint(candidateAudit == null
                            ? providerAudit.getPlaywrightInvocationBaselineHint()
                            : candidateAudit.getPlaywrightInvocationBaselineHint())
                    .build();
        }
        List<TavilyFastLaneAudit> audits = new ArrayList<>();
        if (providerAudit != null) {
            audits.add(providerAudit);
        }
        if (candidateAudit != null) {
            audits.add(candidateAudit);
        }
        return TavilyFastLaneAudit.merge(audits);
    }

    private TavilyFastLaneAudit buildCandidateTavilyFastLaneAudit(List<SourceCandidate> sourceCandidates,
                                                                  List<SearchCollectionTarget> selectedTargets,
                                                                  boolean providerFallbackUsed) {
        if (sourceCandidates == null || sourceCandidates.isEmpty()) {
            return null;
        }
        Map<String, SourceCandidate> uniqueTavilyCandidates = new LinkedHashMap<>();
        for (int index = 0; index < sourceCandidates.size(); index++) {
            SourceCandidate candidate = sourceCandidates.get(index);
            if (!isTavilyCandidate(candidate)) {
                continue;
            }
            uniqueTavilyCandidates.putIfAbsent(resolveTavilyAuditKey(candidate, index), candidate);
        }
        if (uniqueTavilyCandidates.isEmpty()) {
            return null;
        }

        LinkedHashSet<String> queryModes = new LinkedHashSet<>();
        LinkedHashSet<String> queryOrigins = new LinkedHashSet<>();
        LinkedHashSet<String> requestIds = new LinkedHashSet<>();
        LinkedHashSet<String> queryFingerprints = new LinkedHashSet<>();
        LinkedHashMap<String, Integer> rejectionReasons = new LinkedHashMap<>();
        int fastLaneUsableCount = 0;
        int fastLaneRejectedCount = 0;

        for (SourceCandidate candidate : uniqueTavilyCandidates.values()) {
            addDistinctText(queryModes, candidate.getTavilyQueryMode());
            addDistinctText(queryOrigins, resolveTavilyQueryOrigin(candidate));
            addDistinctText(requestIds, candidate.getTavilyRequestId());
            addDistinctText(queryFingerprints, resolveTavilyQueryFingerprint(candidate));
            if (Boolean.TRUE.equals(candidate.getFastLaneUsable())) {
                fastLaneUsableCount++;
                continue;
            }
            fastLaneRejectedCount++;
            rejectionReasons.merge(resolveFastLaneRejectReason(candidate), 1, Integer::sum);
        }

        int queriesSent = !requestIds.isEmpty()
                ? requestIds.size()
                : !queryFingerprints.isEmpty() ? queryFingerprints.size() : queryModes.size();
        boolean bootstrapTriggered = queryOrigins.stream().anyMatch("BOOTSTRAP"::equalsIgnoreCase);
        return TavilyFastLaneAudit.builder()
                .queryModes(new ArrayList<>(queryModes))
                .queryOrigins(new ArrayList<>(queryOrigins))
                .queriesSent(queriesSent)
                .totalResults(uniqueTavilyCandidates.size())
                .fastLaneUsableCount(fastLaneUsableCount)
                .fastLaneRejectedCount(fastLaneRejectedCount)
                .rejectionReasons(rejectionReasons.isEmpty() ? Map.of() : rejectionReasons)
                .bootstrapTriggered(bootstrapTriggered)
                .fallbackTriggered(providerFallbackUsed || fastLaneRejectedCount > 0)
                .tavilyRequestIds(new ArrayList<>(requestIds))
                .playwrightInvocationBaselineHint(countSelectedTavilyFastLaneTargets(selectedTargets))
                .build();
    }

    /**
     * SearchAuditSummary 闂傚倸鍊搁崐鎼佸磹妞嬪海鐭嗗〒姘ｅ亾妤犵偞鐗犻、鏇㈠Χ閸屾矮澹曞┑顔矫畷顒勫储鐎电硶鍋撶憴鍕妞ゎ偄顦遍埀顒勬涧閵堟悂鐛弽顓ф晣闁绘柨鎲￠悵銊モ攽閿涘嫬浜奸柛濠冪墪铻炲ù锝堝€藉☉銏犵妞ゆ挾鍣ラ崑銊╂⒑缂佹ê濮夐柛搴涘€濋幃鈥斥槈濮橈絽浜炬鐐茬仢閸旀艾螖閻樿櫕鍊愰柛鈹惧亾濡炪倖甯婇悞锕€鐣峰畝鈧埀顒侇問閸ｎ噣宕抽敐鍛殾闁圭儤鍤﹂弮鍫濈劦妞ゆ帊鑳堕々鎻捨旈敐鍛殲闁稿鍓濈换娑㈠幢濡ゅ啰顔囧銈呮禋閸嬪懘濡甸崟顖涙櫜闁割偅绻勫В銏㈢磽娴ｄ粙鍝洪悽顖涱殔椤曘儵宕熼姘辩杸闂佸壊鐓堥崑鎺懳涢弽顓熲拻濞达絽鎽滅粔鐑樸亜閵夛附宕岄柕鍡曠劍缁绘繈宕堕‖顒婄畵閺岀喖鎮ч崼鐔哄嚒缂備胶濮垫繛濠囧蓟閻斿吋鍊锋い鎺戝€瑰▓顒勬⒑闁偛鑻晶顕€鏌涢悢鍛婄稇妞ゎ偄绻愮叅妞ゅ繐瀚鍥煙閼圭増褰х紒鎻掓惈鍗遍柛顐犲劜閳锋垿鏌涘┑鍡楊伀濠⒀囦憾閺屾盯骞樼€靛憡鍣梺璇茬箲鐢鎹㈠┑瀣潊闁挎繂妫涢妴鎰渻閵堝骸寮鹃柛鎾跺枎閻ｇ兘鎮界粙鍨祮闂侀潧绻堥崹纭呫亹閸曨垱鈷戦柟鑲╁仜閸旀﹢鏌涢弬璺ㄐч柟顕嗙節婵＄兘鍩℃担铏规毎?Tavily 闂傚倸鍊搁崐宄懊归崶顒夋晪鐟滃酣銆冮妷鈺佺濞撴艾娲﹂弲銏犫攽閻樼粯娑фい鎴濇噹濞插潡姊虹涵鍛汗閻炴稏鍎靛畷婊冣攽鐎ｎ亞锛欓柟鍏肩暘閸斿秹鍩涢幒鎳ㄥ綊鏁愰崨顔兼殘闁荤姵鍔忛崜婵嬪Φ閸曨垼鏁囬柣鎰版涧閳敻鎮楃憴鍕闁告梹鐟╅獮鍐礈瑜屽▽顏堟煙椤栧棗鍟伴崫搴♀攽閻樻剚鍟忛柛鐘愁殜婵￠潧鈽夐姀鐘碉紱闂佽鍎抽悘鍫ュ磻閹捐埖鍠嗛柛鏇ㄥ墰閿涙﹢姊洪幖鐐插婵炲拑缍侀崺鈧い鎺戝€归弳鈺呮煕濡姴娲ら悡婵嬪箹濞ｎ剙濡肩紒鐙呯秮閺岋絽鈻庣仦鎴掑婵?     * 闂傚倸鍊搁崐鎼佸磹妞嬪海鐭嗗〒姘ｅ亾妤犵偛顦甸弫鎾绘偐閸愬弶鐤勯梻浣筋嚃閸ㄥジ鎮橀幇顖樹汗闁圭儤鎸搁埀顒€顭烽弻銈夊箒閹烘垵濮庢繛?report / replay / 闂傚倸鍊搁崐鎼佸磹閻戣姤鍤勯柛顐ｆ礀閸屻劎鎲搁弮鍫㈠祦闁哄稁鍙庨弫鍐煥閺囨浜剧紒鎯у⒔閹虫捇鍩為幋锔藉亹閻庡湱濮撮ˉ婵堢磼閻愵剙鍔ゆい顓犲厴瀵濡搁妷銏℃杸闂佺硶鍓濋敋濞寸姵鍎抽埞鎴︽倷閸欏鏋欐繛瀛樼矋缁诲牆鐣烽幇鐗堝€婚柤鎭掑劤閸樺墽绱掗悙顒佺凡鐎规洦鍓氶弲鍫曨敍濞戞牔绨婚柟鑲╄ˉ濡插懎螣閳ь剙鈹戦纭峰姛缂侇噮鍨崇划顓㈡偄閻撳海鍊為悷婊冮叄閹﹢寮撮悙鈺傛杸闂佸疇妫勫Λ妤佺濠婂牊鍊垫慨妯煎帶閻忥箑鈹戦敍鍕毈妤犵偛娲鍓佹崉閵娧冨箑婵犵數濮伴崹鐓庘枖濞戙垺鍎楅柛顐ｇ贩瑜版帒绀嬫い鏍ㄧ▓閹锋椽姊婚崒姘卞濞撴碍顨呭嵄闁割偅娲橀悡鏇㈠箹缁厜鍋撶€圭姴鐓橀梻浣哥枃濡嫰藝閹跺壙鍥亹閹烘挾鍘?summary 闂傚倸鍊搁崐鎼佸磹妞嬪海鐭嗗〒姘ｅ亾鐎规洏鍎抽埀顒婄秵娴滃爼鎮㈤崱妯圭箚妞ゆ牗绻傞崥褰掓⒒閸曨偄顏柡宀嬬節瀹曟﹢濡搁妷銏犱壕闁煎鍊楁稉宥夋煛閸屾侗鍎ラ柣鏂挎閹綊鎼归悷鏉垮闂佸湱娅㈢紞渚€寮婚敐澶娢ч柛灞剧煯婢规洟姊婚崒姘偓椋庣矆娓氣偓楠炲鏁嶉崟顓犵厯闂佸湱鍎ら崺鍫ユ倿閼恒儳绠鹃柛鈩冾殘缁犳岸鏌＄€ｎ亞效闁哄本娲濈粻娑氣偓锝庝簴閸嬫捇寮介鐔蜂簵濠电偞鍨崹娲偂閺囥垺鍊堕柣鎰絻閳锋棃鏌嶉挊澶樻Ч闁靛洤瀚幆鏃堝閵忋垻鍘掗梻浣筋嚃閸ｎ噣宕伴弽顓炵厺閹兼番鍔岀粻濠氭倵閻㈡鐒炬鐐村姍濮婅櫣鎷犻幓鎺戞瘣缂傚倸绉村Λ婵嗙暦閹达箑绀嬫い鎺嶇瀵潡姊洪幖鐐插姶闁告挻宀搁幃陇绠涘☉娆戝幈闂侀潧顦伴崹鐢碘偓姘煎櫍閸╃偛鈽夊杈╋紳婵炶揪绲介幖顐ｇ墡闂備胶鍎甸弲鈺呭垂閸洖违濞撴埃鍋撶€殿喗鎸虫慨鈧柣妯荤垹閸ャ劎鍘卞┑鐐村灥瀹曨剟鐛弽銊ｄ簻闁圭儤鎸鹃惌鎺撴叏?     */
    private SearchAuditSummary buildSearchAuditSummary(List<SourceCandidate> allCandidates,
                                                       List<SearchCollectionTarget> attemptedTargets,
                                                       List<SearchCollectionTarget> selectedTargets,
                                                       List<SourceCandidate> discardedCandidates,
                                                       SearchExecutionTrace executionTrace,
                                                       TavilyFastLaneAudit tavilyFastLaneAudit) {
        return SearchAuditSummary.builder()
                .candidateCount(allCandidates == null ? 0 : allCandidates.size())
                .selectedCount(selectedTargets == null ? 0 : selectedTargets.size())
                .discardedCount(discardedCandidates == null ? 0 : discardedCandidates.size())
                .attemptedCount(attemptedTargets == null ? 0 : attemptedTargets.size())
                .degraded(executionTrace == null ? null : executionTrace.getDegraded())
                .degradationReason(executionTrace == null ? null : executionTrace.getDegradationReason())
                .fallbackDecision(executionTrace == null ? null : executionTrace.getFallbackDecision())
                .recoveryCheckpoint(executionTrace == null ? null : executionTrace.getRecoveryCheckpoint())
                .sourceUrls(executionTrace == null || executionTrace.getSelectedUrls() == null
                        ? List.of()
                        : executionTrace.getSelectedUrls())
                .fieldEvidenceQueryCount(executionTrace == null ? 0 : executionTrace.getFieldEvidenceQueryCount())
                .fieldEvidenceQueryPlannedCount(executionTrace == null ? 0 : executionTrace.getFieldEvidenceQueryPlannedCount())
                .fieldEvidenceQueryExecutedCount(executionTrace == null ? 0 : executionTrace.getFieldEvidenceQueryExecutedCount())
                .fieldEvidenceQuerySkippedCount(executionTrace == null ? 0 : executionTrace.getFieldEvidenceQuerySkippedCount())
                .fieldEvidenceQuerySkipReasons(executionTrace == null || executionTrace.getFieldEvidenceQuerySkipReasons() == null
                        ? Map.of()
                        : executionTrace.getFieldEvidenceQuerySkipReasons())
                .fieldEvidenceFields(executionTrace == null || executionTrace.getFieldEvidenceFields() == null
                        ? List.of()
                        : executionTrace.getFieldEvidenceFields())
                .fieldEvidencePaths(executionTrace == null || executionTrace.getFieldEvidencePaths() == null
                        ? List.of()
                        : executionTrace.getFieldEvidencePaths())
                .tavilyFastLaneAudit(tavilyFastLaneAudit)
                .build();
    }

    private FieldEvidenceExecutionStats resolveFieldEvidenceExecutionStats(ResolvedFieldEvidenceQueryPlan fieldEvidenceQueryPlan,
                                                                           TavilyFastLaneAudit providerAudit) {
        int plannedCount = fieldEvidenceQueryPlan == null ? 0 : fieldEvidenceQueryPlan.getPlanned().size();
        Map<String, Integer> skipReasons = new LinkedHashMap<>();
        if (fieldEvidenceQueryPlan != null) {
            mergeIntegerCounters(skipReasons, fieldEvidenceQueryPlan.getSkipReasons());
        }
        List<FieldEvidenceQueryExecutionAudit> queryAudits = providerAudit == null
                || providerAudit.getFieldEvidenceQueryExecutions() == null
                ? List.of()
                : providerAudit.getFieldEvidenceQueryExecutions();
        if (queryAudits.isEmpty()) {
            int executableCount = fieldEvidenceQueryPlan == null ? 0 : fieldEvidenceQueryPlan.getExecutable().size();
            int skippedCount = fieldEvidenceQueryPlan == null ? 0 : fieldEvidenceQueryPlan.getSkipped().size();
            return new FieldEvidenceExecutionStats(plannedCount, executableCount, skippedCount, skipReasons, 0L);
        }
        int executedCount = 0;
        int providerSkippedCount = 0;
        long elapsedMillis = 0L;
        for (FieldEvidenceQueryExecutionAudit audit : queryAudits) {
            if (audit == null) {
                continue;
            }
            elapsedMillis += audit.getElapsedMillis() == null ? 0L : Math.max(0L, audit.getElapsedMillis());
            if ("SKIPPED".equalsIgnoreCase(audit.getStatus())) {
                providerSkippedCount++;
                String reason = StringUtils.hasText(audit.getSkipReason())
                        ? audit.getSkipReason().trim()
                        : "SKIPPED_UNKNOWN";
                skipReasons.merge(reason, 1, Integer::sum);
                continue;
            }
            executedCount++;
        }
        int coordinatorSkippedCount = fieldEvidenceQueryPlan == null ? 0 : fieldEvidenceQueryPlan.getSkipped().size();
        return new FieldEvidenceExecutionStats(
                plannedCount,
                executedCount,
                coordinatorSkippedCount + providerSkippedCount,
                skipReasons,
                elapsedMillis
        );
    }

    private String buildFieldEvidenceSupplementCompletionMessage(ResolvedFieldEvidenceQueryPlan fieldEvidenceQueryPlan,
                                                                 TavilyFastLaneAudit providerAudit) {
        FieldEvidenceExecutionStats stats = resolveFieldEvidenceExecutionStats(fieldEvidenceQueryPlan, providerAudit);
        return "field query plan " + stats.getPlannedCount()
                + ", executed " + stats.getExecutedCount()
                + ", skipped " + stats.getSkippedCount()
                + ", elapsed " + stats.getElapsedMillis()
                + "ms, skipReasons=" + (stats.getSkipReasons().isEmpty() ? "[]" : stats.getSkipReasons());
    }

    /**
     * 闂傚倸鍊搁崐宄懊归崶顒夋晪鐟滃繘鍩€椤掍胶鈻撻柡鍛箘閸掓帒鈻庨幘宕囶唺濠碉紕鍋涢惃鐑藉磻閹捐绀冩い鏃傚帶閼板灝鈹戦悙鏉戠伇濡炲瓨鎮傚?query 闂傚倸鍊搁崐鎼佸磹閻戣姤鍤勯柛顐ｆ磸閳ь兛鐒︾换婵嬪礃閳轰礁浼庨梻渚€娼ч悧鍡浰囬锕€鐒垫い鎺戯功閻ｇ數鈧娲栭妶鍛婁繆閻戣姤鏅查柛灞剧煯婢规洟姊洪崨濠勨槈闁宦板姂瀵彃鈹戠€ｎ偆鍘遍柣蹇曞仧閸嬫捇鎯冮幋锔界厽闁圭偓娼欓悘銉︺亜椤忓嫬鏆ｅ┑鈥崇埣瀹曘劑顢涘搴℃暪濠电姷顣藉Σ鍛村磻閸涘瓨鍋￠柍杞扮贰閸ゆ洟鎮楅崷顓炐ラ柣銈傚亾闂備胶鎳撹ぐ鐐烘嚄閸洖绠犻柟鍓у劦閳ь剨绠撴俊鎼佸煛娴ｄ警妲版俊鐐€栧ú鏍箠婢舵劕纾婚柟鐐窞閺冣偓閹峰懘宕楅崫銉ф晨闂傚倷娴囬～澶婄暦濡　鏋嶉柡鍥ュ灩缁犵喖鏌熼梻瀵稿妽闁绘挻鐟ч埀顒傛嚀鐎氫即宕戞繝鍥х？闁哄啫鐗婇悡鏇㈡煏婵炵偓娅囬柣锝嗘そ閺岀喎鐣烽崶褉鏋呭銈冨灪椤ㄥ﹤鐣烽幒鎴旀婵☆垵宕垫禒宀勬⒒閸屾艾鈧悂宕愭搴ｇ焼濞撴埃鍋撴鐐差樀閺佹捇鎮╅崘韫敾婵＄偑鍊栭悧婊堝磻濞戙垹鍨傞柛宀€鍋為悡鐔兼煏韫囧鈧牗绂嶉妶鍥╃＝鐎广儱妫涙晶鐢告煛鐏炲墽娲村┑锛勫厴椤㈡瑩宕ｉ妷锔炬綎闂傚倷绶氬鑽も偓闈涚焸瀹曘垽骞栨担鍝ワ紱闂佺懓澧界划顖炲疾閺屻儱绠圭紒顔煎帨閸嬫捇鎳犻鍌氱厓闂傚倸鍊烽懗鍫曗€﹂崼銉晞闁糕剝绋戠粻鎻掋€掑锝呬壕缂佲剝鎹囬弻娑氫沪閸撗呯厒缂佺偓鍎抽妶绋款嚕閸洖閱囨繛鎴灻‖瀣磽娴ｅ搫啸闁稿鍠栭崺鈧い鎺戝枤濞兼劖绻涢崣澶屽⒌闁诡喓鍎茬缓鐣岀矙鐠恒劎鏆梻浣稿暱閹碱偊骞婃惔锝囦笉婵炴垶鈼よぐ鎺撴櫜闁搞儱澧庨崝鎼佹煣濮瑰洤鈧繂顫?coordinator 濠电姷鏁告慨鐑藉极閹间礁纾婚柣鎰惈缁犳澘鈹戦悩宕囶暡闁稿骸绉电换婵囩節閸屾粌顣虹紓浣插亾閻庯綆鍋佹禍婊堟煙閸濆嫮肖闁告柨绉归弻锝夋晜閽樺浼屽┑顔硷功缁垶骞忛崨瀛樺殟闁靛绲洪崑鎾诲礃椤旂晫鍘介梺纭呮閸嬫盯銆呴鍌滅＜妞ゆ梻鏅幊鍥殽閻愬弶鍠樻い銏＄懇閹稿﹥寰勫Ο鍦┛闂傚倸鍊烽懗鍓佸垝椤栨凹娼栧┑鐘宠壘閸屻劎鎲搁弬璺ㄦ殾闁圭儤顨呮儫闂侀潧顦崹娲棘閳ь剟姊绘担铏瑰笡闁挎岸鏌ｉ妶鍛缂佹梻鍠庤灒闁煎鍊楅鏇㈡⒑閸涘﹣绶遍柛瀣閹﹢寮婚妷锔惧幐闁诲繒鍋涙晶浠嬪煡婢跺浜滄い鎰剁悼缁犵偞銇勯姀鈽嗘畷闁瑰嘲鎳橀幃閿嬪鐎涙﹫绱┑鐘垫暩婵兘寮幖浣哥；婵炴垯鍨洪崑瀣攽閻樺弶鎼愰柛銊ュ€块幃瑙勬姜閹峰矈鍔呴梺缁樺笩婵倝濡甸崟顔剧杸闁圭偓娼欏▍褏绱撴担鍙夘€嗛柛瀣尰缁绘繂鈻撻崹顔界亶闂佹寧娲嶉弲鐘茬暦濠婂喚娼╁Λ鐗堢箖閺呮粓姊虹捄銊ユ灁濠殿喚鏁婚崺娑㈠箣閿旂晫鍘卞┑鐐村灦閿曨偄顔忛妷褏纾兼俊銈勮兌椤ｆ煡鏌曢崶褍顏┑鈩冩倐婵＄兘顢欑憴锝嗙€版繝鐢靛Х椤ｄ粙宕滃┑瀣闁告劘灏欓弳锔芥叏濡炶浜惧銈冨灪閻熲晠骞婇悙鍝勎ㄩ柨鏃€鍎冲?     * 闂傚倸鍊搁崐鎼佸磹閹间礁纾瑰瀣椤愪粙鏌ㄩ悢鍝勑㈢紒鈧崼鐔虹闁糕剝蓱鐏忎即鏌涙繝鍛厫缂佺粯绻堝Λ鍐ㄢ槈閸楃偛澹堥梻浣侯焾鐞氼偊宕濋幋锕€钃熼柡鍥ュ灩闁卞洦绻濋崹顐㈠缁楁垹绱撻崒娆掑厡濠殿垼鍙冨畷浼村冀椤撶偟鍘撮梺纭呮彧闂勫嫰宕戦幇鐗堢厵缂備焦锚閸у﹪鏌涚€ｎ偅灏扮紒缁樼箓椤繈顢楁担鐣屽彂濠电姵顔栭崰妤呪€﹂崼銉ユ槬闁哄稁鍘介崑?planned query 闂傚倸鍊搁崐鎼佸磹妞嬪海鐭嗗〒姘ｅ亾妤犵偞鐗犻、鏇㈠Χ閸屾矮澹曞┑顔筋焽閸樠勬櫠椤曗偓濡焦寰勬繝搴㈠瘜闁诲函缍嗘禍顏堫敁濡ゅ懏鐓曢柕濠忓缁犵偤鏌ｉ幙鍐ㄤ喊鐎规洖鐖兼俊姝岊槷濠殿喖娲铏圭磼濡纰嶉梺绋匡工椤兘宕洪埀顒併亜閹哄棗浜剧紓浣哄Т缁夌懓鐣烽弴銏＄劶鐎广儱鎳愰鍡涙煟鎼搭垳绉甸柍褜鍓﹂崣蹇曞緤閸撗勫床婵犻潧妫鈺傘亜閹捐泛孝妤犵偛鐗撳缁樻媴閸濄儳楔闂佺顑呴敃顏勭暦閵徛板亝闁告劑鍔庨悿鍥⒑閸涘﹤濮﹂柛鐘愁殜閹?trace / summary闂?     */
    private List<String> resolveDistinctFieldEvidenceFields(List<FieldEvidenceQuery> fieldEvidenceQueries) {
        return fieldEvidenceQueries.stream()
                .map(FieldEvidenceQuery::getFieldName)
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .toList();
    }

    private List<String> resolveDistinctFieldEvidencePaths(List<FieldEvidenceQuery> fieldEvidenceQueries) {
        return fieldEvidenceQueries.stream()
                .map(FieldEvidenceQuery::getEvidencePathKey)
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .toList();
    }

    private boolean isTavilyCandidate(SourceCandidate candidate) {
        return candidate != null && "tavily".equalsIgnoreCase(candidate.getProviderKey());
    }

    /**
     * repair 闂傚倸鍊搁崐宄懊归崶顒夋晪鐟滃酣銆冮妷鈺佺濞撴艾娲﹂弲銏犫攽閻樼粯娑фい鎴濇噹濞插潡姊虹涵鍛汗閻炴稏鍎靛畷婊冣攽鐎ｎ亞锛欓柟鍏肩暘閸斿秹鍩涢幒妤佺厱閻忕偛澧介幊鍛存煕閺傝法肖闁瑰弶鎮傚鍫曞垂椤旇姤顔掗梻浣筋嚃閸ㄤ即藝閻㈢绠栭柍鈺佸暞閸庣喐淇婇婵嗗惞濞?sourceUrl 闂傚倸鍊搁崐鎼佸磹妞嬪海鐭嗗〒姘ｅ亾妤犵偛顦甸弫鎾绘偐閸愯弓鐢绘俊鐐€栭悧妤冪矙閹炬眹鈧懘寮婚妷锔惧弳濠电娀娼уΛ顓炍ｉ崨濠冧氦婵犻潧顑嗛埛鎴︽煕濞戞﹫宸ュ┑顔瑰亾闂備礁鎼幊蹇曞垝鎼达絾顫曢柣鎰惈閻愬﹥銇勯幒宥堫唹闁瑰嘲鎼埞鎴︽倷閸欏妫￠梺绋垮婵炲﹪骞冨Ο琛℃瀻闁规儳顕崢閬嶆煟鎼搭垳绉甸柛瀣噽娴滄悂顢橀悢缈犵盎濡炪倖鎸荤划灞炬叏閸岀偞鐓曢柍瑙勫劤娴滅偓淇婇悙顏勨偓鏍暜閹烘纾归柟闂寸閸ㄥ倹绻濋棃娑卞剱闁绘挾鍠愭穱濠囶敍濞戝崬鍔岄梺鎼炲€栭悷銉╂箒闂佸吋绁撮弲娑欑墡闂備線娼уú銈団偓姘煎灣缁鈽夐姀鐘殿啋闂佹儳娴氶崑鍛搭敊閹烘鈷掑ù锝堫潐閸嬬娀鏌涙繝鍐╃妞ゃ垺鐗犲畷鍫曨敆閳ь剟鎮為崹顐犱簻闁瑰搫绉剁拹浼存煕閻旈绠婚柡灞剧洴閹晛鐣烽崶褉鍚傞梻浣风串缁插潡宕楀鈧妴浣肝旈崨顓狀槹濡炪倖鍔戦崐妤呭储椤掑嫭鈷掑ù锝堟閵嗗﹪鏌涢幘瀵哥畼缂侇喗鐟╅獮瀣晜閽樺澹掓繝寰锋澘鈧洟骞婅箛娑欏亗闁靛濡囩弧鈧梻鍌氱墛缁嬫帡藟閻樼粯鐓曢柣鎰仛閺嗩剟鏌″畝瀣瘈鐎规洖鐖兼俊鐑藉Ψ瑜岄幃锝夋⒒娴ｈ銇熼柛妯圭矙閵嗗啯绻濋崶褑鎽曢梺缁樻閵嗏偓闁稿鎸搁埥澶娾枎濡厧濮洪梻浣规た閸樺ジ鎯岄崒鐐茶摕婵炴垯鍨洪崑鍕煕閹存瑥鈧盯宕ラ锔解拺閻犲洠鈧櫕鐏曞┑鐐差槹閻╊垶宕洪妷锕€绶為柟閭﹀墮閸炪劑鎮峰鍐ｉ柟渚垮姂閺佸啴宕掑☉鎺撳闂傚倸鍊搁悧鍐疾濠靛牃鍋撻棃娑栧仮闁哄本绋戣灃濞达絿纭堕弸娆撴倵鐟欏嫭绀€缂傚秴锕悰顕€宕卞鍏夹ラ梻浣姐€€閸嬫挸霉閻樺樊鍎愰柣鎾存礋閺岋繝宕堕…瀣典簼娣囧﹪鎮￠獮?     * 闂傚倸鍊搁崐椋庣矆娓氣偓楠炴牠顢曚綅閸ヮ剦鏁冮柨鏇楀亾闁汇倗鍋撶换婵囩節閸屾粌顤€闂佺顑戠换婵嬪蓟瀹ュ浼犻柛鏇ㄥ墮濞咃綁姊婚崒姘簽闁搞劏娉涢～蹇撁洪鍕€銈嗘礀閹冲酣宕滈崘宸富闁靛牆鍊瑰▍鍥煕韫囨棑鑰块柣娑卞櫍瀵粙濡搁敂鍓ら梻浣告啞閹稿棝宕橀妸褍甯撶紓鍌氬€搁崐鐑芥嚄閼稿灚鍙忛柣銏犳啞閸庡孩銇勯弽顐粶闁藉啰鍠栭弻銊╂偄閸濆嫅銏ゆ⒑閸楃偞澶勭紒缁樼箞瀹曞綊顢曢姀鐙€娼旂紓浣诡殕閸ㄥ灝顫忕紒妯肩懝闁逞屽墴閸┾偓妞ゆ帒鍊告禒婊堟煠濞茶鐏￠柡鍛埣椤㈡盯鎮欑€电骞楅梻浣虹帛閺屻劌顕ｇ捄琛℃瀺濠电姴娲﹂悡鏇㈡煃鐟欏嫬鍔ゅù婊呭亾娣囧﹪鎮欓鍕ㄥ亾閺嵮屽晠濠电姵鑹剧壕濠氭煙閻愵剛鏆樺ù婊勭矒閺屻劑寮捄銊よ檸閻庤鎸稿Λ婵嬪蓟閻旂⒈鏁嶆慨妯哄船椤ｅ搫顪冮妶搴″箲闁告梹鍨甸悾鐑芥偄绾拌鲸鏅ｅ┑鐐村灦閻熝勭濞嗗繆鏀介柨娑樺娴滃ジ鏌涙繝鍐⒌妤犵偞鍔欓幃娆擃敄鐠恒劎鐣惧┑鐘绘涧閸婂鈥﹂崼銏㈠暗鐎广儱顦伴悡鏇㈡倶閻愪絻妾告繛鍫熸煥闇夋繝濠傜墢閻ｆ椽鏌熼鐓庢Щ闁宠姘︾粻娑㈠箼閸愌呯＝婵犵數濮伴崹濂革綖婢舵劏鈧箓宕堕鈧粻鐔兼煙闂傚顦︾紒鐙欏洦鐓欑紒瀣健椤庢绱掗幇顓犮€掔紒杈ㄦ尰缁楃喖宕惰閻濐噣姊洪崨濠勬噧闁哥喐鎸虫俊瀛樼瑹閳ь剙顕ｆ禒瀣垫晝闁挎繂鎳愰敍蹇斾繆閻愵亜鈧倝宕㈡禒瀣瀭鐎规洖娲﹂～鏇熸叏濡炶浜惧┑顔硷功缁垶骞忛崨鏉戝窛濠电姴鍟崜鍨繆閻愵亜鈧呪偓闈涚焸瀹曟垶绻濋崶褎妲梺閫炲苯澧柕鍥у楠炴帡宕卞鎯ь棜闂傚倷绀侀幗婊堝窗閺囩偐鏋栭柡鍥ュ灩缁狀垶鎮楅敐搴℃灈闁藉啰鍠栭弻鏇熷緞閸繂濮㈡俊銈囧Т濞差厼顫忓ú顏勭闁告瑥顦伴崕鎾绘煟鎼淬垹鍤柛鎾寸箞閵嗗倿宕稿Δ浣叉嫽?URL闂傚倸鍊搁崐鎼佸磹閻戣姤鍊块柨鏃堟暜閸嬫挾绮☉妯诲櫧闁活厽鐟╅弻鐔衡偓鐢殿焾娴犙囨⒒閸曨偄顏柡宀嬬節瀹曟﹢濡搁妷銏犱壕闁煎鍊楁稉宥夋煛閸屾侗鍎ラ柣鏂挎閺屻倝骞栨担瑙勯敪婵犳鍠栭悧鎾诲蓟閿濆绠婚柛鎰ゴ閸嬫挻绻濆顒傚姦濡炪倖宸婚崑鎾剁磼閻樿尙效鐎规洘娲熷畷锟犳倷瀹ュ棛鈽夐柍璇查叄楠炴﹢寮堕幋婊呮殫闂傚倷绶氶埀顒傚仜閼活垱鏅剁€涙ɑ鍙忓┑鐘叉噺椤忕姷绱掗鐣屾噧闁宠閰ｉ獮鍡氼槻濠㈢懓顦扮换婵嗏枔閸喗鐏嶉柤鍨﹀洦鐓曢柡鍌涘閹癸絾淇婇崣澶婂闁诡喗鐟╁鍫曞箣閻樼數宓侀梺璇查閸樻粓宕戦幘缁樼厓鐟滄粓宕滃☉娆戠彾闁哄洢鍨圭粈鍌炴煠濞村娅囬柣锕€鐗婄换婵嬫偨闂堟刀銏ゆ煕婵犲啯鍊愭い銏℃閸╋繝宕ㄩ闂村寲闁荤喐绮岀粔褰掑箖濞差亜惟鐟滃骸鐣烽崣澶岀闁瑰瓨鐟ラ悘鈺冪磼閻欐瑥娲﹂悡娆撴煙绾板崬骞栨鐐寸墵閺屾盯骞樼拋铏枤濠殿喖锕ュ浠嬬嵁閺嶎厽鍊烽柟缁樺垂閳哄懏鈷戦柛娑橈攻閳锋劙鏌涢妸銉﹀仴闁诡喕鍗抽、姘跺焵椤掑嫮宓侀柟鐑樺殾閺冨牆鐒垫い鎺戝€绘稉宥夋煙閹澘袚闁绘挻鐟╅幃妤呮偨濞堣法鍔搁柛鐔侯焾椤啴濡惰箛鏇犐戦梺缁樻尨閳ь剚鍓氬鏍煣韫囨凹娼愰柛鐘叉閺屾盯寮撮妸銈嗘崳闂佺硶鏅涢幊妯侯潖濞差亜宸濆┑鐘插暟閸欏棛绱撴担鍓叉Ш闁硅櫕鎹囬、姘舵晲閸℃瑧鐦堝┑顔斤供閸撴盯宕愰悙宸富闁靛牆妫楅崸濠囨煕鐎ｎ偅灏版繛?     */
    private String resolveFirstRecoverySourceUrl(List<SourceCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return "";
        }
        for (SourceCandidate candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            if (StringUtils.hasText(candidate.getUrl())) {
                return candidate.getUrl();
            }
            if (candidate.getSourceUrls() == null) {
                continue;
            }
            for (String sourceUrl : candidate.getSourceUrls()) {
                if (StringUtils.hasText(sourceUrl)) {
                    return sourceUrl;
                }
            }
        }
        return "";
    }

    private String resolveTavilyAuditKey(SourceCandidate candidate, int index) {
        if (candidate != null && StringUtils.hasText(candidate.getUrl())) {
            return candidate.getUrl().trim();
        }
        if (candidate != null && StringUtils.hasText(candidate.getPrefetchedContentRef())) {
            return candidate.getPrefetchedContentRef().trim();
        }
        if (candidate != null && StringUtils.hasText(candidate.getTavilyRequestId())) {
            return candidate.getTavilyRequestId().trim() + "#" + index;
        }
        return "tavily#" + index;
    }

    private String resolveTavilyQueryFingerprint(SourceCandidate candidate) {
        if (candidate == null) {
            return null;
        }
        String queryMode = StringUtils.hasText(candidate.getTavilyQueryMode())
                ? candidate.getTavilyQueryMode().trim()
                : "";
        String query = StringUtils.hasText(candidate.getTavilyQuery())
                ? candidate.getTavilyQuery().trim()
                : "";
        if (!StringUtils.hasText(queryMode) && !StringUtils.hasText(query)) {
            return null;
        }
        return queryMode + "::" + query;
    }

    private String resolveFastLaneRejectReason(SourceCandidate candidate) {
        if (candidate == null) {
            return "UNKNOWN";
        }
        if (StringUtils.hasText(candidate.getFastLaneRejectReason())) {
            return candidate.getFastLaneRejectReason().trim();
        }
        if (StringUtils.hasText(candidate.getPageType())) {
            return candidate.getPageType().trim();
        }
        if (StringUtils.hasText(candidate.getQualityTier())) {
            return candidate.getQualityTier().trim();
        }
        return "UNKNOWN";
    }

    /**
     * Tavily 闂傚倸鍊搁崐宄懊归崶顒夋晪鐟滃酣銆冮妷鈺佺濞撴艾娲﹂弲銏犫攽閻樼粯娑фい鎴濇噹濞插潡姊虹涵鍛汗閻炴稏鍎靛畷婊冣攽鐎ｎ亞锛欓柟鍏肩暘閸斿秹鍩涢幒鎳ㄥ綊鏁愰崨顔兼殘闂佸摜鍠撻崑銈夊蓟閿濆鍋嗗ù锝呮憸娴狀厾绱撴担绋库偓鍝ョ矓瑜版帇鈧線寮崼婵堫槹闂佸疇顫夐崕铏妤ｅ啯鐓欓柣鎴烇供濞堟梻绱掗埀顒勫醇濠㈡繂缍婇弫鎰板炊閵娿儲鐣梻浣告惈濡參宕戦崨顖涘床婵炴垶鐟︾紞鍥煕閹炬瀚禒瑙勪繆閻愵亜鈧倝宕㈡總鍛婂亱闁圭偓鍓氬鏍煙椤栧棗鏈娲⒑闁偛鑻晶瀵糕偓娈垮枦椤曆囧煡婢舵劕顫呴柣妯活問閸炴椽姊绘担鐑樺殌妞ゆ洦鍙冨畷鏇㈠箛椤戔晜绋栫粻娑樷槈濞嗗本瀚奸梻鍌欑贰閸嬪棝宕戝☉銏″殣妞ゆ牗绋掑▍鐘炽亜閺嶃劎鐭岀痪鎯у悑閵囧嫰寮崶褌姹楃紓浣哄У婵炲﹪寮婚悢鑲╁祦闁割煈鍠氭导鍫ユ倵濞堝灝鏋涢柍褜鍓涢崳銉ノｉ悜鑺モ拺闁告繂瀚烽崕蹇涙⒑鐢喚绉€殿喛顕ч鍏煎緞婵犲嫬骞愬┑鐐舵彧缁蹭粙骞夐垾鏂ユ灁闁割偅娲橀埛鎴︽煕濞戞﹫鍔熼柟鎻掓健閺屾稓鈧綆鍋呭畷灞炬叏婵犲啯銇濋柟绛圭節婵″爼宕ㄩ崨顐㈢仾缂佺粯绋撴禒锕傚磼濮樺彉绱欓柣搴ゎ潐濞叉繈锝炴径宀€绱﹂柣锝呯灱閻瑩鎮规笟顖滃帥闁哥偛缍婂缁樻媴閻熼偊鍤嬬紓浣筋嚙閸婂潡鐛繝鍐ㄧ窞闁归偊鍓涢崫妤呮⒑閹稿孩绀€闁稿﹤缍婇幃鈥斥枎閹剧补鎷哄銈嗘尪閸斿海妲愰幍顔剧＜闁靛鍊楅惌娆撴煛?Phase 1 bootstrap 闂傚倸鍊搁崐椋庣矆娓氣偓楠炴牠顢曚綅閸ヮ剦鏁冮柨鏇楀亾闁汇倗鍋撶换婵囩節閸屾侗妫￠梺鐟板暱閺堫剛鎹㈠┑鍫濇瀳婵☆垰鎼埛澶愭偡濠婂嫭绶查柛鐔告尦閻涱喗寰勯幇顒備紜闂佸綊顣﹂懗鍫曞礉闁垮绡€闁汇垽娼ф牎闂佽偐鎳撴晶鐣屽垝鐠囨祴妲堟繛鍡楃С缁ㄥ姊洪崫鍕ら柡浣告憸缁瑦绻濆顓犲幐闂佺鏈〃鍛妤ｅ啯鈷?supplement闂傚倸鍊搁崐鎼佸磹閻戣姤鍤勯柛顐ｆ礀绾惧鏌曟繛鐐珔缁炬儳娼￠弻銈囧枈閸楃偛顫悗瑙勬礃閻擄繝寮婚悢鍏肩劷闁挎洍鍋撻柡瀣枑閵?     * 闂傚倸鍊搁崐鎼佸磹閻戣姤鍊块柨鏇炲€哥粻鏍煕椤愶絾绀€缁炬儳娼￠弻褑绠涢敐鍛凹闂佸憡鐟ョ换姗€寮婚敐澶婎潊闁绘ê妯婂Λ宀勬⒑鏉炴壆顦︽俊顐ｇ懅閹广垹鈹戠€ｎ偒妫冨┑鐐村灦閻熴儵藝閳哄倻绠鹃悗娑櫭▓鐘绘煕婵犲啰澧遍柟骞垮灩閳藉濮€閻樿尪鈧灝鈹戦埥鍡楃仴妞ゆ泦鍛瀳鐎广儱鎳夐弨浠嬫煟閹邦剙绾ч悗姘噽缁辨帞绱掑Ο鑲╃杽閻庢鍠栭…鐑藉箖閵忋倖鍊绘俊顖濇閿涘繘姊绘担铏瑰笡缁炬澘绉撮…鍥р枎閹邦剦鍤ら梺瑙勫婢ф鍩涢幋锔解拺妞ゆ劑鍊曟禒婊堟煠濞茶鐏￠柡鍛埣椤㈡盯鎮欑€电骞楅梻浣虹帛閺屻劌顕ｇ捄琛℃瀺濠电姴娲﹂悡鏇㈡煃鐟欏嫬鍔ゅù婊呭亾娣囧﹪鎮欓鍕ㄥ亾閺嵮屽晠濠电姵鑹剧壕濠氭煙閻愵剛鏆樺ù婊勭矒閺屻劑寮撮悙娴嬪亾閻熸壋妲堢憸鏃堝箺閸洘鏅查柛姘ュ€曠紞濠囧极閹版澘鐐婇柍鐟扮氨閸嬫挻绻濋崶銊у幐闁诲函缍嗛崑鍛此夐崼鐔稿弿濠电姴鍋嗛悡鑲┾偓瑙勬礀閵堟悂骞冮姀銈呯畳闁瑰搫妫楁禍楣冩煙閻戞ɑ鈷掔痪鍓у帶椤法鎹勯悮瀛樻暰闂佽鍨伴悧鎾诲蓟瀹ュ瀵犲鑸瞪戦埢鍫澪旈悩闈涗沪闁挎洏鍨介妴浣糕槈濮楀棙鍍靛銈嗗姂閸╁嫬螞濠婂牊鈷?BOOTSTRAP / SUPPLEMENT 濠电姷鏁告慨鐑藉极閹间礁纾婚柣鎰惈閸ㄥ倿鏌涢锝嗙缂佺姳鍗抽弻娑㈩敃閿濆棛顦ョ紒鐐礃椤绌辨繝鍥ч柛娑卞枛閻濇梻绱撻崒姘毙ｉ柣妤冨█瀵鎮㈤崗鐓庘偓缁樹繆椤栨粌鍔﹂柟宄邦煼濮婂搫煤鐠囨彃绠洪梺鑽ゅ暱閺呯姴顕ｇ拠娴嬫闁靛繒濮烽悿鈧俊鐐€栭悧妤€顫濋妸銉愭帡濮€閵堝棌鎷洪梺鍛婄箓鐎氼參藟濞嗘挻鐓曟俊銈勭閳绘洜鈧鍠栭…鐑藉垂妤ｅ啫绠涘ù锝呮啞閸婎垶姊绘担鍛婂暈濞撴碍顨婂畷浼村冀椤愩倗骞撳┑鐐村灦閿曗晛銆掓繝姘厪闁割偅绻冮ˉ婊呯磼濡烇箑娲﹂悡鏇㈢叓閸ャ劍顥栭柤鎷屾硶閳ь剚顔栭崳顕€宕戦崟顖ｆ晣濠靛倻顭堝婵囥亜閺嶃劎鈯曢柣锝囧厴濮婄粯鎷呴崨濠傛殘濠电偠顕滈梽鍕矉瀹ュ鍊烽柛顭戝亽濞肩喎鈹戦绛嬬劸闁糕晜鐗犻崺娑㈠箣閻樼數锛滈柣搴秵閸樼晫娑甸崜浣虹＜闁绘ê鍟块ˉ瀣磼鏉堛劌绗氱€垫澘瀚换婵嬪礋閸撲胶顦紓鍌氬€风欢锟犲闯椤曗偓瀹曪綁宕橀妸褎娈鹃梺鍦劋閸╁牓鎮￠妷鈺傜厓鐟滄粓宕滃▎鎾崇厺濞寸姴顑愰弫鍌炴煕椤愩倕鏋旈柛姗€浜堕弻锝堢疀閺囩偘绮舵繝鈷€鍌滅煓闁诡垰鐬奸埀顒婄秵閸犳鎮￠弴銏＄厓闁告繂瀚埀顒€顭锋俊鎾箳閺冨倻锛滈柡澶婄墑閸斿秶浜搁幍顔剧＜婵°倕鍟弸娑氣偓瑙勬礃閸庡ジ藝閼碱剛纾奸柣妯烘▕閻撳吋鎱ㄦ繝鍛仩闁归濞€閸ㄩ箖鎼归銈勯偗闂傚倷鐒︾€笛兠洪埡鍐笉闁规崘宕靛畵浣割熆閼搁潧濮囩紒鐘电帛閵囧嫰寮崶顬挻绻涢崨顔剧煉婵﹥妞藉畷銊︾節娴ｈ櫣绠掗梻浣告啞鐪夌紒顔界懃椤曪綁骞庨挊澹┿劑鏌嶉崫鍕偓鐢稿箯?stage 缂傚倸鍊搁崐鎼佸磹閹间礁纾归柟闂寸绾剧懓顪冪€ｎ亝鎹ｉ柣顓炴闇夐柨婵嗙墕閳ь剝妫勯埥澶婎潨閸℃瑥寮抽梻浣告啞濞诧附绂嶉悙鐢典笉婵﹩鍓﹀〒濠氭煏閸繃顥為悘蹇ｄ邯閺屾盯濡歌閺€鎵磼?     */
    private String resolveTavilyQueryOrigin(SourceCandidate candidate) {
        if (candidate == null) {
            return null;
        }
        if ("TAVILY_PHASE1_BOOTSTRAP".equalsIgnoreCase(candidate.getDiscoveryMethod())) {
            return "BOOTSTRAP";
        }
        if (!StringUtils.hasText(candidate.getSelectionStage())) {
            return null;
        }
        String selectionStage = candidate.getSelectionStage().trim().toUpperCase(Locale.ROOT);
        if ("BOOTSTRAPPED".equals(selectionStage)) {
            return "BOOTSTRAP";
        }
        if ("SUPPLEMENTED".equals(selectionStage)) {
            return "SUPPLEMENT";
        }
        return null;
    }

    private void addDistinctText(Set<String> values, String value) {
        if (values == null || !StringUtils.hasText(value)) {
            return;
        }
        values.add(value.trim());
    }

    private void mergeIntegerCounters(Map<String, Integer> target, Map<String, Integer> additions) {
        if (target == null || additions == null || additions.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Integer> entry : additions.entrySet()) {
            if (entry == null || !StringUtils.hasText(entry.getKey())) {
                continue;
            }
            target.merge(entry.getKey().trim(), entry.getValue() == null ? 0 : entry.getValue(), Integer::sum);
        }
    }

    private int countSelectedTavilyFastLaneTargets(List<SearchCollectionTarget> selectedTargets) {
        if (selectedTargets == null || selectedTargets.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (SearchCollectionTarget selectedTarget : selectedTargets) {
            SourceCandidate candidate = selectedTarget == null ? null : selectedTarget.getCandidate();
            if (isTavilyCandidate(candidate) && Boolean.TRUE.equals(candidate.getFastLaneUsable())) {
                count++;
            }
        }
        return count;
    }

    private static Integer resolveFieldEvidencePriority(FieldEvidenceQuery query) {
        if (query == null || query.getPriority() == null) {
            return Integer.MAX_VALUE;
        }
        return query.getPriority();
    }

    private static String resolveFieldEvidenceFingerprint(FieldEvidenceQuery query) {
        if (query == null || !StringUtils.hasText(query.getQueryFingerprint())) {
            return null;
        }
        return query.getQueryFingerprint().trim();
    }

    private SearchRuntimePolicy resolveRuntimePolicy(CollectorNodeConfig config) {
        SearchRuntimePolicy existing = config.getSearchRuntimePolicy();
        if (existing != null) {
            return existing;
        }
        return SearchRuntimePolicy.builder()
                .recoveryHint("resume from VERIFY_TOP_CANDIDATES or BROWSER_SUPPLEMENT_SEARCH after the blocker is cleared")
                .build();
    }

    private String resolveRecoveryCheckpoint(SearchExecutionPlan executionPlan) {
        if (executionPlan == null || executionPlan.getSteps() == null) {
            return "LOAD_CANDIDATES";
        }
        return executionPlan.getSteps().stream()
                .filter(step -> step.getStatus() == SearchExecutionStep.StepStatus.SUCCESS
                        || step.getStatus() == SearchExecutionStep.StepStatus.RUNNING
                        || step.getStatus() == SearchExecutionStep.StepStatus.FAILED
                        || step.getStatus() == SearchExecutionStep.StepStatus.SKIPPED)
                .reduce((first, second) -> second)
                .map(SearchExecutionStep::getStepCode)
                .orElse("LOAD_CANDIDATES");
    }

    private String buildRecoveryAdvice(boolean circuitBroken,
                                       String degradationReason,
                                       BrowserSearchRuntimeResult browserSearchResult,
                                       List<SearchCollectionTarget> selectedTargets,
                                       CollectorNodeConfig config) {
        if (circuitBroken && StringUtils.hasText(degradationReason)) {
            return "search timed out before "
                    + resolveRecoveryStepForReason(degradationReason)
                    + "; resume from that step after the timeout condition is removed";
        }
        if (browserSearchResult != null && StringUtils.hasText(browserSearchResult.getBlockedReason())) {
            return "browser search was blocked [" + browserSearchResult.getBlockedReason()
                    + "]; consider adjusting user-agent, domain strategy, or retrying supplement";
        }
        if (selectedTargets == null || selectedTargets.isEmpty()) {
            return "no selected targets yet; inspect blockedDomains, referredDomains, and generated queries";
        }
        SearchRuntimePolicy policy = resolveRuntimePolicy(config);
        if (policy != null && StringUtils.hasText(policy.getRecoveryHint())) {
            return policy.getRecoveryHint();
        }
        return "resume from SELECT_TARGETS or COLLECT_PAGES after checking the latest candidate pool";
    }

    private String resolveRecoveryStepForReason(String degradationReason) {
        return switch (degradationReason) {
            case "SEARCH_TIMEOUT_BEFORE_VERIFY" -> "LOAD_CANDIDATES";
            case "SEARCH_TIMEOUT_BEFORE_SUPPLEMENT" -> "VERIFY_TOP_CANDIDATES";
            case "SEARCH_TIMEOUT_AFTER_SUPPLEMENT" -> "BROWSER_SUPPLEMENT_SEARCH";
            default -> "SELECT_TARGETS";
        };
    }

    private String buildSupplementRunningMessage(CollectorNodeConfig config) {
        List<String> queries = config.getSearchQueries() == null
                ? List.of()
                : config.getSearchQueries().stream().filter(StringUtils::hasText).toList();
        if (queries.isEmpty()) {
            return "run supplement search using planned search queries";
        }
        String engine = browserSearchRuntimeService.getSearchEngineName();
        String prefix = StringUtils.hasText(engine) ? " via " + engine + " " : " via browser search ";
        if (queries.size() == 1) {
            return "run supplement search" + prefix + "query=" + queries.get(0);
        }
        return "run supplement search" + prefix + "queries=" + queries.size() + ", firstQuery=" + queries.get(0);
    }

    /**
     * 闂傚倸鍊峰ù鍥х暦閻㈢绐楅柟閭﹀枛閸ㄦ繈骞栧ǎ顒€鐏繛鍛У娣囧﹪濡堕崨顔兼缂備胶濮抽崡鎶藉蓟閻斿吋鈷掗悗鐢殿焾婵′粙姊虹粙娆惧剭闁稿﹥顨婇獮澶岀矙濞嗘儳鎮戞繝銏ｆ硾閿曪箓宕㈠ú顏呪拺閻熸瑥瀚崝銈夋煟鎺抽崝宥夊礆婵犲洤绠绘い鏃傛櫕閸橀亶姊虹憴鍕凡闁告埃鍋撶紓浣靛妼閸氬濡甸崟顖ｆ晝闁靛繆鎳ｈ閹?query 濠电姷鏁告慨鐑藉极閹间礁纾绘繛鎴欏焺閺佸銇勯幘璺烘瀾闁告瑥绻橀幃妤呮濞戞瑦鍠愰梺娲诲幗閹瑰洭骞冨Δ鍛棃婵炴垶鐟ラ弳鍫濐渻閵堝棗濮冪紒顔界懇瀵鏁愭径濠勭潉闂佺鏈懝鐐濡偐纾藉ù锝呮惈琚ㄩ梺绋垮婵炲﹪鐛径鎰濞达絽鎽滈娲⒑閹稿孩顥嗛柕鍡忓亾闂佺顑嗛幐鑽ょ箔閻旂厧鐒垫い鎺戝瀹撲線鎮楅敐搴℃灍闁哄懏绻堥弻宥堫檨闁告挻鐩幃顕€骞嗚閸氬顭跨捄渚剰濞寸媭鍨辩换娑欐綇閸撗冨煂闂佺顕滅槐鏇㈠箯瑜版帗鏅柛鏇ㄥ幘閿涙粓姊洪柅鐐茶嫰婢ь垳鈧灚婢樼€氫即鐛崶顒夋晢闁稿瞼鍋熷畷婊堟⒒閸屾瑧顦﹂柛鐔锋健楠炴牠顢曢敃鈧壕褰掓煕濞戞﹫鏀婚柛娆忕箻閹﹢鎮欓弶鍨彑婵炲瓨绮嶇划鎾诲蓟閺囷紕鐤€濠电偞鍎虫禍鍓р偓瑙勬礀濞诧絿妲愬┑瀣厽閹兼番鍩勯崯蹇涙煕閻樺啿鍝虹€殿噮鍋呯换婵嗩潩椤掑倻鏆梻渚€娼х换鍫ュ磹閺囩姷鐭嗛柛鏇ㄥ灡閻撴稑顭跨捄渚剾闁稿簺鍎叉穱濠囧矗婢跺鈧劙鏌＄仦鐣屝ч柟顔惧厴瀵爼骞愭惔銏╁晪濠德板€楁慨鐑藉磻濞戞◤娲敇椤兘鍋撴担绯曟瀻闁圭偓娼欐禒濂告煟韫囨洖浠ч柛瀣崌椤㈡瑨绠涢弮鍌滅槇缂佸墽澧楄摫妞ゎ偄锕弻娑氣偓锝庡亝鐏忕數鈧灚婢橀敃銉╁Χ閿濆绀冮柍杞版缂冩洟姊绘担铏瑰笡闁告梹鐗楃粋宥嗙鐎ｎ亞鍔﹀銈嗗坊閸嬫捇鏌熼搹顐€跨€殿噮鍋婂畷姗€顢欓懖鈺嬬床婵犵數鍋為崹鍫曟晪婵犮垻鎳撳Λ娆愮┍婵犲洦鍊锋い蹇撳閸嬫捇寮借濞兼牕鈹戦悩瀹犲闁汇倗鍋撻妵鍕箛閸撲胶鏆犻梺鎼炲妼閸婃悂鍩為幋锕€纾兼慨姗嗗幖閺嗗牓姊虹紒妯诲鞍闁烩晩鍨跺璇测槈閵忕姴宓嗛梺闈涚箚閸撴繈鏌ㄩ銏♀拺閻犲洩灏欑粻鍐测攽閻愨晛浜鹃梻浣告惈閺堫剛绮欓幋锝囦航闂備胶鍘ч～鏇㈠磹閺嶎厼绀嗘繛鎴烆焸?field-first 闂傚倸鍊搁崐鎼佸磹妞嬪海鐭嗗〒姘ｅ亾妤犵偞鐗犻、鏇㈠Χ閸℃ぞ绮℃俊鐐€栭崝褏绮婚幋鐘差棜闁秆勵殕閻撴瑧绱撴担闈涚仼婵炲懏锕㈤弻鈩冩媴閸撴彃鍓辨繛锝呮搐閿曨亝淇婇崼鏇炲窛妞ゆ柨鍚嬮鐘绘⒒娴ｈ銇熼柛妯圭矙閵嗗啯绻濋崶褑鎽曢梺缁樻閵嗏偓闁稿鎸搁埥澶娾枎濡厧濮虹紓鍌欒兌婵敻骞愭繝姘疄闁靛ň鏅滈弲鏌ユ煕濠娾偓閻掞箓鍩㈤崼銉︹拺闁告繂瀚﹢鎵磼鐎ｎ偄鐏遍柣蹇斿浮濮婃椽妫冮埡浣烘В闂佸憡顭堝▍锝夊箟娴兼潙骞㈡繛鍡樺灩閿?     * 闂傚倸鍊搁崐鎼佸磹妞嬪海鐭嗗〒姘ｅ亾鐎规洏鍎抽埀顒婄秵娴滃爼鎮㈤崱妯圭箚妞ゆ牗绻傞崥褰掓⒒閸曨偄顏柡宀嬬節瀹曟﹢濡搁妷銏犱壕闁煎鍊楁稉宥夋煛閸屾侗鍎ラ柣鏂挎閺屻倝骞栨担瑙勯敪婵犳鍠栧ú顓㈠蓟濞戞ǚ鏋庨煫鍥ュ劜閺侀箖姊洪棃娑欐悙閻庢碍婢橀锝嗙鐎ｎ€晝鎲歌箛娑欏亗濞撴埃鍋撴慨濠呮閸栨牠寮撮悢鍝ュ絾缂傚倷绀侀鍡涘箰閹灛锝夊箛閺夎法顔掗柣搴ㄦ涧閹芥粓骞婂┑瀣拺闁硅偐鍋涢崝鈧梺鍛婂姦娴滃爼宕崶顒佲拺閻犲洤寮堕崬澶嬨亜椤愩埄妯€闁诡喗妞藉鎾偄娓氼垱閿ゆ俊鐐€栫敮鎺斺偓姘煎墰缁顢涘☉鏍︾盎闂佽婢樻晶搴ㄦ偩鏉堚晝纾奸柍褜鍓熷畷姗€顢欓悾灞藉笚闂備礁鎲＄换鍌溾偓姘煎墴瀵娊寮崼鐔哄幈闁诲函绲芥晶搴ㄦ偩閻㈠憡鐓涚€光偓鐎ｎ剛袦闂佽鍠撻崹钘夌暦椤愶箑唯闁挎洍鍋撴繛鍛灴濮婄粯鎷呴搹鐟扮濡炪們鍔岄幊搴ょ亱濠德板€曢幊搴ㄦ偂濠靛牃鍋撻獮鍨姎妞わ富鍨崇划濠氬箮閼恒儳鍘甸梺璇″瀻閸愮偓娈洪柣鐔哥矆閸楀磭绮婚弽褜娼栭柧蹇氼潐閸忔粓鏌涘☉鍗炴灓闁靛棙鍔曢—鍐Χ鎼粹€茬凹濠电偠灏欓崰鏍х暦濞差亜鐒垫い鎺嶉檷娴滄粓鏌熼崫鍕棞濞存粍鍎抽—鍐Χ韫囨洜鏆ゆ繛瀛樼矊閻栧ジ鎮伴鈧畷姗€鍩℃笟鍥ф闂備礁鎼ˇ浼村吹閿曞倸惟闁冲搫鍊婚崣鍡涙⒑閸濆嫬鈧悂鎮樺┑瀣厱闁哄啫鐗婇悡鏇㈡煃鏉炴媽鍏岄弫鍫濃攽椤旂》鏀绘俊鐐扮矙閻涱噣骞囬鐔峰妳闂佹寧绻傞崐鎼侊綖瀹€鈧槐鎾诲磼濞嗘埈妲銈嗗灥濡繈骞冭椤劑宕煎┑鍫敼闂備線娼х换鍫ュ磹閺囥垹绀冮柍褜鍓欓—鍐Χ閸℃ê鏆楅梺绋款儐閻╊垰鐣烽鍕╅柍鍝勫€甸幏缁樼箾鏉堝墽鍒伴柟鑺ョ矋缁傛帡濮€鎺虫禍婊堟煙鐎涙绠栨い銉ｅ灲閺屸剝鎷呴崫銉愶絿鈧灚婢樼€氫即鐛崶顒夋晩闁绘挸娴风€靛ジ姊婚崒娆戝妽闁诡喖鐖煎畷鏇㈩敍閻愯尙顦繝鐢靛Т閸熶即銆呴悜鑺ョ厽婵☆垵鍋愮敮娑㈡煟閹惧啿鏆熼柟鑼焾椤劑宕煎┑鍫Н婵犵數鍋為崹璺侯潩閵娾晜鍎楅柛鈩冾樅瑜版帗鏅查柛顐亜濞堟瑥鈹戦悙鍙夆枙濞存粍绮撳畷姗€鍩€椤掆偓椤啴濡堕崱妯烘殫闂佺顑囬崰鏍х暦椤愨懡鏃堝川椤旇瀚奸梺鑽ゅТ濞茬娀鍩€椤掆偓绾绢參鍩€椤掆偓椤兘寮诲☉銏犲嵆闁靛鍎伴懜顏呯箾鐎电甯堕柟鍐查叄閸╃偤骞嬮敂钘夆偓鐑芥煠绾板崬澧柟鑼跺亹缁辨挻绗熼崶褎鐏堥梺娲诲幖閸婂潡鎮伴鈧浠嬵敇瑜庨弲婵嬫⒑閹稿海绠撻柟宄邦儔瀹曠敻濡舵径瀣ф嫼闂侀潻瀵岄崢濂搞€傞崗鑲╃瘈闁靛繆妲呴悞鐣岀磼椤旂⒈鐓兼鐐查叄閹崇偤濡疯楠炲秹姊绘担铏瑰笡闁搞劌鐖奸弫鍐煛娴ｅ弶鐏侀梺鍝勬川閸犲棙绂嶅鍫熺厸闁稿本绋戦婊堟煕閻樻垚鎴﹀箞閵婏妇绡€闁告劏鏂傛禒銏狀渻閵堝啫鐏柣鐔濆嫮顩查柟闂寸閸愨偓閻熸粌鏈粩鐔煎即閵忊檧鎷虹紓鍌欑劍钃遍柣鎾卞劦閺屾盯濮€閿涘嫬寮ㄩ梺缁樹緱閸犳岸鍩€椤掑﹦绉甸柛鐘愁殜閸╂盯骞嬮敂鐣屽幈濠电娀娼уΛ妤咁敂閳哄倶浜滈煫鍥э攻濞呭棛绱掔紒妯笺€掗柍褜鍓涢弫鎼佲€﹂崼銉ュ偍闁圭虎鍠楅悡鏇㈡煏婵犲繒鐣遍柛鏂诲€栭妵鍕敃閿濆洨鐓夐梺绯曟杹閸嬫挸顪冮妶鍡楃瑨閻庢凹鍓涚划璇差潩閼哥數鍘遍梺闈涱槶閸ㄦ椽寮惰ぐ鎺撶厱閻庯綆鍋呭畷宀勬煛娴ｇ懓濮堥柟顖涙煥閳规垿宕奸姀鈩冩珤闂傚倸鍊烽懗鍓佸垝椤栫偛绠伴柟闂寸绾剧懓鈹戦悩瀹犲缂佺姷鍠栭弻鐔兼⒒鐎电濡介梺绋款儏椤戝寮诲☉銏╂晝闁靛牆鎳忛悗鑽ょ磼閻愵剚绶茬紒澶屾暩閹广垹鈹戠€ｎ亞鍘遍梺閫炲苯澧寸€规洏鍔戦、妯诲緞濞戞氨袦闂佽鍠楅〃濠囧箖娴犲顥堟繛鎴灻肩划顖炴⒑閸欍儳涓茬紓宥勭窔瀵鈽夊Ο閿嬫杸闂佺硶鍓濋〃蹇旂婵傚憡鍊垫繛鍫濈仢閺嬬喐銇勯妸銉уⅱ婵″弶鍔欓獮妯尖偓娑櫭鎾绘⒑閸涘﹦绠撻悗姘煎枦閵囨劕鈻庨幇顔绢啎闁哄鐗嗘晶鐣岀矙閼姐倖鍠愰柡澶婄仢閺嗙偟绱?     */
    private String buildFieldEvidenceSupplementRunningMessage(CollectorNodeConfig config) {
        List<FieldEvidenceQuery> fieldEvidenceQueries = resolveFieldEvidenceQueries(config);
        if (fieldEvidenceQueries.isEmpty()) {
            return buildSupplementRunningMessage(config);
        }
        FieldEvidenceQuery firstQuery = fieldEvidenceQueries.get(0);
        String firstField = StringUtils.hasText(firstQuery.getFieldName())
                ? firstQuery.getFieldName()
                : "UNKNOWN_FIELD";
        String firstQueryText = StringUtils.hasText(firstQuery.getQuery())
                ? firstQuery.getQuery()
                : "UNKNOWN_QUERY";
        return "run field-evidence supplement with " + fieldEvidenceQueries.size() + " queries; firstField="
                + firstField
                + ", firstQuery="
                + firstQueryText;


    }

    /**
     * 闂傚倸鍊搁崐鎼佸磹閻戣姤鍤勯柛顐ｆ磵閳ь剨绠撳畷濂稿Ψ閿旇姤鐝栭梻渚€娼чˇ顐﹀疾濠婂牆纾婚柨鐔哄У閻撳啴鏌涘┑鍡楊仾闁革絽缍婇弻锝夊箳閹寸姳绮甸梺闈涙搐鐎氫即鐛幒妤€骞㈡俊鐐村劤椤ユ艾鈹戦敍鍕杭闁稿ě鍕箚闁搞儺鍓欓弸浣糕攽閻樺疇澹樼紒鐘崇墵閺屸剝寰勬繝鍕殤闂佸搫顑呴柊锝夊蓟閻旇　鍋撻悽娈跨劸濞寸姵绮撻弻锝呪攽閸繍鏆紓浣虹帛缁嬫帒顭囪箛娑樼鐟滃酣宕戣椤啴濡舵惔鈥崇闂佺粯顨呯换姗€鐛箛鏇楀亾閿濆骸鏋涢柦鍐枛閺屾洘寰勫☉姗嗘喘闂佸憡锕㈢粻鏍蓟閿濆棙鍎熼柍鈺佸暢绾偓濠电姵顔栭崰姘跺极閺勫繑锛傚┑鐘灱濞夋盯寮甸鍕獥闁规壆澧楅悡娑橆熆鐠虹尨鍔熷ù鐘灲閺屽秷顧侀柛鎾寸懃椤洭鏁撻悩闈涚ウ闂佸憡鍔忛弲婵嬨€呴悜鑺ュ€甸柨婵嗛娴滄粌霉濠婂嫮鐭掓慨濠勭帛閹峰懘宕妷銈堟婵＄偑鍊栭崹鐢告偋閹惧磭鏆﹂柕澶嗘櫓閺佸啴鏌ㄩ弮鍌涙珪闁告ü绮欏铏圭磼濡崵鍙嗛梺纭呭Г缁捇鐛繝鍥х妞ゆ棁妫勯埀顒€鐏氶幈銊ノ熼悡搴′粯婵犫拃鍐惧殶闁逞屽墲椤煤濠婂牆绠犻柟鎹愵嚙閻撴﹢鏌熺€电袥闁稿鎹囬弫鎰償濠靛牏娉块梻浣虹帛缁诲啴鏁冮姀鐙€娼栭柧蹇撴贡绾惧吋淇婇婵嗗惞闁告瑥妫濆鍝勑ч崶褍顬夌紓浣筋嚙閻楀棝鎮鹃悜钘夌闁挎洍鍋撶紒鐘差煼閺岋繝宕掑Ο鍝勫闂佽楠搁…宄邦潖濞差亜鎹舵い鎾墲椤庢姊洪幖鐐插濠⒀勵殘缁顓兼径濠勭暰閻熸粍绮撻幃銏ゅ幢濞戞瑥浠梺鎯х箳閹虫捇鎯冮幋锔界厸闁逞屽墯缁绘繂顫濋鐐╁亾閸偅鍙忔慨妤€妫楅獮姗€鏌涘▎灞戒壕闂傚倷娴囬鏍窗濞戞ǚ鏋栨繛鎴欏灩閻撯€愁熆閼搁潧濮囬柛鎰ㄥ亾闁荤喐绮岀粔鐢稿箞閵娾晛鍗抽柣妯兼暩閿涙繃绻涢幘纾嬪婵炲眰鍔戝畷銏犫槈閵忥紕鍘遍梺鎸庣箓缁绘帡宕氭导瀛樼厽闁挎繂娲ら崢瀛橆殽閻愬弶鍠樻い銏＄墵閹崇偤濡疯閸嬫挻绻濆顓涙嫼闂侀潻瀵岄崣鈧俊鐐倐閺屾盯濡歌椤ｈ偐绱掗崒姘毙ф鐐叉椤т線鏌ｉ弬鎸庮棦闁诡喛顫夊顏堝箯瀹€濠傚Τ婵犵鍓濊ぐ鍐Χ閹间礁钃熺€广儱鎳愰弳瀣煙濞堝灝鏋涙い锝嗘そ濮婅櫣鈧湱濮甸ˉ澶嬨亜閿旂偓鏆柣娑卞枛閳诲酣骞樺畷鍥舵О闁荤喐绮嶅Λ鍐ㄧ暦閹存惊鏃堝焵椤掑嫬鐓橀柟杈剧畱閻擄繝鏌涢埄鍐炬畼濞寸姍鍛＝濞达絾褰冩禍鐐箾閹炬潙鐒归柛瀣崌閺屾盯鍩為崹顔句紙濡ょ姷鍋為…鍥╂閹烘嚦鐔烘偘閳ュ厖澹曢梺缁樺灱婵倝鎮￠弴銏㈠彄闁搞儯鍔嶉幆鍕繆閼碱剦鐒鹃棁?     * 闂傚倸鍊搁崐椋庣矆娓氣偓楠炴牠顢曚綅閸ヮ剦鏁冮柨鏇楀亾闁汇倗鍋撶换婵囩節閸屾粌顤€闂佺顑戠换婵嬪蓟瀹ュ浼犻柛鏇ㄥ墮濞咃綁姊婚崒姘簽闁搞劋鍗抽垾鏃堝礃椤忎礁浜鹃柨婵嗙凹缁ㄥジ鏌熼惂鍝ユ偧闁汇儺浜獮鍡氼槹闁稿鍨介弻鈥崇暆鐎ｎ剛袦閻庢鍣崜鐔风暦瑜版帩鏁嬮柛娑卞枟椤旀垵鈹戦敍鍕杭闁稿﹥娲滈幑銏犖熼懡銈庢锤闂佸壊鍋呭ú鏍嫅閻斿摜绠鹃柟瀵稿仜閻掑綊鏌涚€ｎ偅宕岄柡浣瑰姈閹棃鍨鹃幓鎺戞瘣婵犵數濮伴崹濂革綖婢舵劏鈧箓宕堕鈧粻鐔兼煙闂傚顦︾紒鐙€鍨堕幃姗€鎮欓崹顐ｇ彧濠殿噯绲婚崹鑽ゆ閹惧鐟归柛銉戝嫮浜堕柣搴″帨閸嬫捇鏌熼梻瀵割槮缂佺姴婀遍幉鎼佹偋閸繄鐟ㄩ梺鍦攰閸╂牠濡甸崟顖氱閻犺櫣鍎ら悘渚€姊洪崨濠冣拹闁诡喖鍊搁～蹇曠磼濡顎撻梺鍛婄☉閿曘倝寮抽崼婵冩斀妞ゆ梻銆嬮崝鐔虹磼椤曞懎鐏︾€规洘纰嶇换婵嬪礋閵娿儰澹曢梻鍌氱墛缁嬫帡宕悙娣簻妞ゆ劧绲剧粈鍐磼缂佹绠炴俊顐㈠暙閳藉宕￠悙鎻掝棊闂傚倷鑳剁划顖滄暜閹烘围闁归棿绀侀拑鐔哥箾閹存梹鍣伴柍褜鍓ㄧ粻鎾荤嵁鐎ｎ亖鏀介柛銉㈡櫃闁垶姊?trace 闂傚倸鍊搁崐鎼佸磹妞嬪海鐭嗗〒姘ｅ亾妤犵偛顦甸弫鎾绘偐閸愯弓鐢绘俊鐐€栭悧妤冪矙閹炬眹鈧懘寮婚妷锔惧弳濠电娀娼уΛ顓炍熼崼銉︾厸濞达綁娼婚煬顒勬煛鐏炲墽娲村┑锛勫厴椤㈡瑩鎳￠妶鍡橆唫婵犵數鍋涢悺銊у垝瀹€鈧槐鐐寸節閸パ嗘憰闂佽法鍠撴慨鎾倷婵犲洦鐓冮弶鐐村閸斿秹鏌ｆ惔锝呬壕缂佺粯绋撻埀顒傛暩椤牊鐗庨梻浣瑰濞插繘宕归挊澶樺殨閻犲洦绁村Σ鍫熸叏濡も偓濡瑩宕撻棃娑辨富闁靛牆妫楃粭鎺楁煕婵犲啯鍊愮€规洜鏁婚、妤呭磼濠婂拑绱冲┑鐐舵彧缂嶁偓妞ゎ偄顦甸崺娑㈠箛椤撶姷顔曢梺绋跨箺閸嬫劙骞婇崶顒佺厱闁崇懓鐏濋悘顏堟煙椤栨稒顥堝┑鈩冩倐閺佸倿鎳為妷顔绘埛闂傚倸鍊烽懗鍓佸垝椤栫偞鏅濋柕蹇曞閻掔晫鎲搁弮鍫濈畾閻忕偠濞囬弮鍫濆窛妞ゆ梹鍎抽獮妤呮⒒娴ｈ櫣甯涢柨姘扁偓娈垮枛閻栧ジ骞嗙仦杞挎梹鎷呴搹璇″晭闂備礁鎼ˇ浼村垂閸洖绐楁慨妞诲亾闁哄本鐩崺鐐哄箚瑜屾竟鏇炩攽閿涘嫬浜奸柛濠冪墪椤斿繑绻濆顒傦紱闂佸湱鍋撻弸濂稿绩娴犲鐓曢悘鐐插⒔閳洟鏌ｉ幘鍗炲姦闁哄苯绉归幐濠冨緞濡儵鏋呮俊鐐€戦崹鍝勎涢崘銊ф殾闁靛ň鏅╅弫鍥煟濡吋鏆╃€规挷绶氬濠氬磼濞嗘埈妲梺纭咁嚋缁绘繈濡撮崘顔奸唶闁靛鍎抽敍娑樷攽閻愭潙鐏嶉柟绋款儔閹粓鎸婃径濠勭崺婵＄偑鍊栭幐鑽ょ矙閹烘缍栫€广儱顦伴埛鎴︽煕閿旇寮ㄦ俊鐐倐閺屾盯濡歌椤ｈ偐绱掗崒姘毙ф鐐叉椤︻噣鏌￠埀顒佺鐎ｎ偆鍘介梺褰掑亰閸撴瑧鐥閺岋綀绠涚€ｎ亖鍋撳┑瀣摕闁哄洢鍨归柋鍥ㄧ節闂堟稒顥戦柛瀣崌瀹曘劍绻濋崟鍨敜闂備胶绮崝锕傚礂濞戞碍宕查柛鈩冪⊕閻撳繘鏌涢锝囩畵妞ゅ浚鍋婇弻娑㈠棘鐠恒剱锝夋煏閸パ冾伂闁绘柧绶氶弻娑㈠Ψ閿濆懎顬嬮梺鍛娒畷顒勫煘閹达附鍋愰悹鍥囧啩绱ｉ梻浣告憸閸犲秹宕￠幎钘夌畺闁绘劖鎯屽Σ褰掑箹鏉堝墽鎮奸柨娑欑矋娣囧﹪顢曢妶鍜佹毉缂備浇顕ч崐鎼侇敋閿濆鏁婇梺娆惧灠娴滈箖鎮峰▎蹇擃仾缂佲偓閸愨斂浜滈柕濞垮劵閺€璇睬庨崶褝韬柟顔界矌閹叉挳鏁愰崒姘闂佹寧娲栭崐鍝ョ不閿濆鐓熼柟閭﹀枟閺嗏晠宕崨濠勭瘈闁汇垽娼у暩濡炪倧缍€濡嫬宓勯梺鍛婄⊕濞兼瑩宕ョ憴鍕╀簻闊洦鎸炬晶鏇犵磼閻橆喖鍔﹂柡灞剧洴楠炲洭顢涘鍗烆槱濠?     */
    private static class VerificationStatsAggregate {

        private long elapsedMillis;
        private int maxConcurrency;
        private int inputCount;
        private int uniqueCount;
        private int reusedCollectedPageCount;
        private int directAttemptCount;
        private int directUsableCount;
        private int directShortcutCount;

        private void add(CandidateVerificationResult result) {
            if (result == null) {
                return;
            }
            elapsedMillis += value(result.getVerificationElapsedMillis());
            maxConcurrency = Math.max(maxConcurrency, Math.max(1, value(result.getVerificationConcurrency())));
            inputCount += value(result.getInputCandidateCount());
            uniqueCount += value(result.getUniqueCandidateCount());
            reusedCollectedPageCount += value(result.getReusedCollectedPageCount());
            directAttemptCount += value(result.getDirectVerificationAttemptCount());
            directUsableCount += value(result.getDirectVerificationUsableCount());
            directShortcutCount += value(result.getDirectVerificationShortcutCount());
        }

        private long getElapsedMillis() {
            return elapsedMillis;
        }

        private int getMaxConcurrency() {
            return maxConcurrency;
        }

        private int getInputCount() {
            return inputCount;
        }

        private int getUniqueCount() {
            return uniqueCount;
        }

        private int getReusedCollectedPageCount() {
            return reusedCollectedPageCount;
        }

        private int getDirectAttemptCount() {
            return directAttemptCount;
        }

        private int getDirectUsableCount() {
            return directUsableCount;
        }

        private int getDirectShortcutCount() {
            return directShortcutCount;
        }

        private int value(Integer value) {
            return value == null ? 0 : value;
        }

        private long value(Long value) {
            return value == null ? 0L : value;
        }
    }

    /**
     * 闂傚倸鍊搁崐鎼佸磹閻戣姤鍤勯柛顐ｆ磸閳ь兛鐒︾换婵嬪磼濡や胶浜欐繝鐢靛仦閸垶宕瑰ú顏勭柧婵犻潧顑嗛悡蹇撯攽閻愯尙浠㈠┑鈥茬矙閺屻劌鈽夊Ο琛″亾閸︻厽宕叉繝闈涱儐閸嬨劑姊婚崼鐔剁繁婵炲吋宀稿娲川婵犲啫顦╅梺绋款儏鐎氫即鏁愰悙鍝勭闁瑰瓨姊归弬鈧梻浣哥枃濡嫬螞濡ゅ懏鍊堕柣鏃囨绾惧ジ鏌嶈閸撴氨绮悢鐓庣劦妞ゆ帒瀚弰銉╂煏韫囧鈧洜绮婚懡銈囩＝濞达絽顫栭鍛浄婵炲樊浜濋埛鎴︽煕閹剧懓鐨洪柛妯荤洴閺屾稓鈧綆浜濋ˉ銏⑩偓娈垮枟婵炲﹪骞冮埡浣烘殾闁搞儮鏅╅崯搴ㄦ⒒娴ｇ儤鍤€妞ゆ洦鍙冨畷鎴︽倷閸濆嫮鐣鹃梺鍛婃处閸ㄩ亶鎮￠弴鐔虹闁糕剝顨堢粻浼存煛閸℃鎳囬柟钘夌埣閹瑩鎮滃Ο杞板寲闂備浇顕栭崢鐣屾暜閹烘挷绻嗛悹鎭掑妿濡垶鏌熼鍡曠娴狀噣姊洪崫鍕伇闁哥姵鐗犻悰顕€宕卞鍏碱€囬梻浣规偠閸婃宕版惔銊︾畳婵犵數濮撮敃銈嗩殽閹间焦鍊堕柨鏇炲€归悡娑氣偓鍏夊亾閻庯綆鍓涢惁鍫ユ⒑缁洘鏉归柛瀣尭椤啴濡堕崱妤冪懆闂佺锕﹂幊鎾绘偩闁垮绶為柟閭﹀幘閸樺崬鈹戦悩缁樻锭婵☆偅顨婇、鏃堫敃閵堝洨锛滈柣搴秵娴滆泛螣閳ь剙鈹戦纭锋敾婵＄偘绮欓獮鍐倻閽樺顓煎銈嗘婵倝宕伴弽顓熲拻濞达絽鎲￠崯鐐存叏婵犲倹璐￠柡渚囧櫍閹瑩妫冨☉妯间喊闂備礁鍟块幖顐﹀疮閻樿纾婚柟鐐灱濡插牊绻涢崱姗嗙劷闁告梻鍏樺鍝勭暦閸モ晛绗″┑顔硷工缂嶅﹥淇婇悽绋跨妞ゆ柨澧介弶鎼佹⒑閸︻厼浜鹃柛鎾寸鐎靛ジ鍩€椤掍胶绡€婵炲牆鐏濆▍娆戠磼闊厾鐭欓柟顔ㄥ洤绠婚悹鍥皺閻ｅ搫鈹戞幊閸婃洟骞婅箛娑樼；闁靛濡囩粻楣冩煕椤愩倕鏋庢い鏇熺矒閺屸剝绗熼崶褏浠稿┑顔硷攻濡炰粙骞婇悙鍝勎ㄧ憸宥夊焻瑜版帗鐓熼幖娣灮閻擃垶鏌涘Δ鈧崯鍧楊敋閿濆鏁冮柕蹇婃櫅閹垿姊洪崨濠佺繁闁搞劌宕埢鎾诲即閵忊檧鎷洪梺鍛婄☉閿曘儲寰勯崟顐€鐟邦煥閸涱厺鎴峰┑鈥冲级閸旀洟锝炲┑瀣殝缁剧増蓱鐎?execute 濠电姷鏁告慨鐑藉极閹间礁纾婚柣鎰惈閸ㄥ倿鏌涢锝嗙缂佺姳鍗抽弻娑㈠箻濡も偓閸燁偊鎮樻繝鍌ゆ富闁靛牆妫涙晶顒傜磼椤斿ジ鍙勯柟顖氱焸瀹曞ジ濡烽敂鎯у汲闂備礁鎼崐鎼佹倶濠靛鐓曢柡鍐ㄧ墛閻撶喐銇勯幘璺烘灁闁瑰啿娲弻鐔碱敊缁涘鐤侀梺杞扮劍閸旀牠骞嗛弮鍫濈労闁告劦浜欓柇顖氣攽閻樺灚鏆╅柛瀣洴楠炲繘骞嬮悩鍐插簥濠电娀娼ч鍛存嫅閻斿摜绠鹃柟瀛樼懃閻掓椽鏌℃担绋挎殻闁哄本娲濈粻娑欑節閸愮偓缍夋繝纰樻閸嬩線宕愬┑瀣畺閻犺桨璀﹀鈺呮煃瑜滈崜鐔风暦閹偊妲炬俊銈囧Т濞差厼顫忛搹鍏夊亾閸︻厼校闁靛棗鍟撮弻锝夊箳閻愮數鏆ゅΔ鐘靛仦閻楃娀鐛崶顒夋晣婵犲ň鍋撶紒銊ヮ煼濮婃椽骞愭惔銏╂⒖濠碘槅鍋勭€氫即銆佸顒夌叆闁告侗鍨抽敍婊堟煟鎼搭垳绉靛ù婊嗘硾鍗辨繛宸簼閻撳啰鎲稿鍫濈婵浜惌娆撴煙閻戞ɑ绀冮柟鍐叉湰娣囧﹪鎮欓鍐婵炶揪绲介幉鈩冨緞閸曨垱鐓熼柟鎯х摠缁€鍐磼缂佹娲寸€规洖宕灒闁告繂瀚呮笟鈧娲礂閸忚偐鍑″┑鐐点€嬬换婵嗩嚕鐠囨祴妲堥柕蹇婂墲濞呮粓姊虹化鏇炲⒉閼垦兠归悩顔肩伈婵?     * 闂傚倸鍊搁崐鎼佸磹妞嬪海鐭嗗〒姘ｅ亾妤犵偛顦甸弫鎾绘偐閸愬弶鐤勫┑掳鍊х徊浠嬪疮椤愩倐鍋撳顒夋Ч闁靛洤瀚伴獮鎺楀幢濡炴儳顥氬┑锛勫亼閸娿倖绂嶅鍫濈柈闁哄鍨归弳锕傛煙閻戞﹩娈曢柛濠勫厴閺屾稑鈻庤箛锝嗏枔濠碘槅鍋嗛崰鏍ь潖婵犳艾纾兼繛鍡樺灱缁愭姊虹粙娆惧剰闁硅櫕鍔楅崚鎺戔枎閹惧磭顔掗柣鐘叉穿鐏忔瑩宕㈤幘顔解拺闁革富鍘奸崝瀣煙缁嬪灝鈷旂紒顔剧帛閵堬綁宕橀埡鍐ㄥ箰濠电姰鍨煎▔娑㈩敄閸涘瓨鍊垮Δ锝呭暞閻撴洟骞栧ǎ顒€鐏€规洖鐭傞弻鈥崇暆鐎ｎ剛袦闂佽桨绀侀崐濠氬箯閻樼粯鍤戞い鎴濈仢鐎垫煡姊婚崒娆戭槮闁硅绱曢弫顔嘉旈崘鈺傛闂佺偨鍎辩壕顓㈠汲閿旂晫绡€濠电姴鍊归敍宥嗕繆閼碱剙鍘存慨濠冩そ閺屽懘鎮欓懠璺侯伃婵犫拃灞界仭闁靛洤瀚伴、姗€鎮欓弶鎴炵亷婵°倗濮烽崑娑⑺囬幍顔瑰亾闂堟稏鍋㈢€殿喖鐖奸獮瀣攽婵炴儳浜鹃柛顭戝枓閺€浠嬫煟閹邦剙绾фい銉у仱閺屾盯寮埀顒勩€冩繝鍥ф瀬鐎广儱顦粻娑㈡煟濡も偓閻楀啴骞忓ú顏呪拺缂備焦锚婵本淇婇銏狀伃鐎规洘绻傞…銊╁川椤栨粣绱叉俊鐐€栧Λ浣规叏閵堝懐鏆﹂悘鐐缎掗弨浠嬫煃閵夈儳锛嶉柟鐣屽█閺岀喖顢涘☉娆樻婵犳鍠掗崑鎾绘⒑閻愯棄鍔氶柛鐔稿娣囧﹪骞庨懞銉㈡嫽婵犵數濮存鍛婄濠婂嫮绠鹃悘鐐插€搁埀顒佺箞閵嗕線寮介鐐碉紲闂佺粯鍔樼亸娆撴晬濞戙垺鈷戦悷娆忓閸斻倝鏌ｆ幊閸斿秹宕氭繝鍥х妞ゆ棁妫勬禒顓炩攽閻愬弶顥滅紒缁樺姍椤㈡棃顢橀悢鍓佺畾闂佽鍨庨崘鈹晠姊虹€圭媭娼愰柛銊ユ健楠炲啴鍩℃担鍙夌€婚棅顐㈡处閹尖晜绂掗崗鑲╃瘈婵炲牆鐏濋弸娑㈡煕婵犲倹鍋ョ€殿喛顕ч濂稿醇椤愶綆鈧洭姊绘担渚劸缂佺粯鍨圭划娆撳箻鐠哄搫鐏婇梺鍓插亞閸犳劖鍒婇幘顔藉仯闁搞儯鍎遍崝婊勪繆閻愯埖顥夋い鏇稻缁傛帞鈧絽鐏氶弲锝夋⒑缂佹ɑ鐓ラ柟纰卞亝閹便劑鎮滈懞銉㈡嫽婵炴挻鍩冮崑鎾绘煃瑜滈崜娑㈠磻濞戙垺鍤愭い鏍ㄧ⊕濞呯娀鎮楅悽鐢点€婇柛瀣尵閹叉挳宕熼鍌ゆО闂備焦瀵у畝鎼佸蓟閿濆鏅查柛銉戝啫绠ｉ柣搴ゎ潐濞叉牠鎮ユ總绋挎槬闁跨喓濮寸壕鍏肩箾閹寸倖鎴︽倵妤ｅ啯鈷掗柛灞捐壘閳ь剛鍏橀幊妤呭醇閺囩偟锛涢梺闈浥堥弲娑氬閸ф鐓犵痪鏉垮船婢ь垳鈧娲橀悡锟犲蓟瀹ュ牜妾ㄩ梺鍛婃尰缁嬫挸危閹版澘绠抽柟鎼幗鐎靛矂姊洪崨濠冨瘷闁告劦鐓堥悗瀵哥磽閸屾艾鈧绮堟笟鈧、鏍川濮濄倕娲幃浠嬪箹閻愨晛浜惧〒姘ｅ亾鐎殿噮鍣ｅ畷鐓庘攽閸偅效濠碉紕鍋戦崐鏍礉閹达箑鍨傞柤濮愬€楅惌娆撴煙鏉堥箖妾柍閿嬪浮閺屾稓浠﹂崜褎鍣梺鍛婃煥缁夌敻濡甸崟顖氱闁绘劕鐏氶崳浼存倵鐟欏嫭纾婚柛妤€鍟块锝夊箻椤旇棄浜滈柣鐐寸▓閸撴繈寮棃娑掓斀闁绘ê鐏氶弳鈺佲攽椤旇姤缍戦悡銈夋煏閸繍妲归柛搴㈩殕娣囧﹪濡堕崨顔兼闂佺粯鎸婚惄顖炲箖濡ゅ懏鏅插璺侯儐闁款厽绻涚€涙鐭嬮柛鏃€鐟╁?     */
    private static class SupplementExecutionOutcome {

        private final BrowserSearchRuntimeResult browserSearchResult;
        private final List<SourceCandidate> supplementedCandidates;
        private final String supplementMethod;
        private final String fallbackDecision;
        private final boolean providerFallbackUsed;
        private final TavilyFastLaneAudit providerTavilyFastLaneAudit;

        private SupplementExecutionOutcome(BrowserSearchRuntimeResult browserSearchResult,
                                           List<SourceCandidate> supplementedCandidates,
                                           String supplementMethod,
                                           String fallbackDecision,
                                           boolean providerFallbackUsed,
                                           TavilyFastLaneAudit providerTavilyFastLaneAudit) {
            this.browserSearchResult = browserSearchResult;
            this.supplementedCandidates = supplementedCandidates;
            this.supplementMethod = supplementMethod;
            this.fallbackDecision = fallbackDecision;
            this.providerFallbackUsed = providerFallbackUsed;
            this.providerTavilyFastLaneAudit = providerTavilyFastLaneAudit;
        }

        private BrowserSearchRuntimeResult getBrowserSearchResult() {
            return browserSearchResult;
        }

        private List<SourceCandidate> getSupplementedCandidates() {
            return supplementedCandidates;
        }

        private String getSupplementMethod() {
            return supplementMethod;
        }

        private String getFallbackDecision() {
            return fallbackDecision;
        }

        private boolean isProviderFallbackUsed() {
            return providerFallbackUsed;
        }

        private TavilyFastLaneAudit getProviderTavilyFastLaneAudit() {
            return providerTavilyFastLaneAudit;
        }
    }

    /**
     * 闂傚倸鍊搁崐宄懊归崶顒夋晪鐟滃繘鍩€椤掍胶鈻撻柡鍛箘閸掓帒鈻庨幘宕囶唺濠碉紕鍋涢惃鐑藉磻閹捐绀冩い鏃傚帶閼板灝鈹戦悙鏉戠伇濡炲瓨鎮傚?query 闂傚倸鍊搁崐鎼佸磹妞嬪海鐭嗗ù锝夋交閼板潡姊洪鈧粔鐢稿磻閿熺姵鐓欓柟顖滃椤ュ顨ラ悙顏勭伈闁哄苯绉瑰畷顐﹀礋椤愮喎浜剧憸鐗堝笒缁狙囨煙闂傚鍔嶉柍閿嬪笒闇夐柨婵嗘川閹藉倿鏌涢妶鍛殻闁哄本绋栫粻娑㈠籍閸屾稑鍨遍梻浣侯攰濞呮洟銆冩繝鍌滄殾婵犲﹤瀚刊瀵哥磼鐎ｎ厽纭跺ù婊€鍗冲缁樻媴閻熸澘濮㈢紓浣虹帛椤ㄥ﹪鏁愰悙鏉戠窞閻庯綆浜滈悘浣圭節闂堟稑鈧鈥﹂崼銏笉闁规儼濮ら悡娆撴煙椤栨粌顣兼い銉ヮ樀閺?     * 闂傚倸鍊搁崐宄懊归崶顒夋晪鐟滃秹婀侀梺缁樺灱濡嫮绮婚悩缁樼厵闁硅鍔﹂崵娆撴煟閵堝骸鏋熼柕鍥у瀵粙濡歌濡晝绱?coordinator 濠电姷鏁告慨鐑姐€傞挊澹╋綁宕ㄩ弶鎴狅紱闂佸憡渚楅崣搴ㄦ偄閸℃ü绻嗘い鏍ㄦ皑濮ｇ偤鏌涚€ｎ偅灏い顐ｇ箞閹剝鎯旈姀銏犵秵闂傚倷娴囬鏍窗濮樿泛纾婚柟鎹愬煐瀹曞弶绻濋棃娑欙紞婵炲皷鏅滈妵鍕箳閹存繀绨藉┑鈥冲级閹稿啿顫忕紒妯诲闁惧繐绠嶉埀顒€锕弻娑㈠箻鐎靛摜鐤勯梺闈涙閸熸潙鐣烽悡搴樻斀闁规儳鍘滈崑鎾诲锤濡や胶鍘电紓鍌欓檷閸ㄥ綊寮搁悢鍏肩厽?provider 闂傚倸鍊搁崐鎼佸磹閻戣姤鍊块柨鏇楀亾妞ゎ亜鍟撮獮鎰償閿濆孩閿ら梻浣虹帛閸旀洟骞栭銈囩幓婵°倕鎳庣粻瑙勭箾閿濆骸澧┑鈥茬矙閺屾稓鈧絽鍚€闁垶鏌＄仦鍓ф创鐎殿喗鎸虫俊鎼佸Ψ瑜岄崫妤冪磽閸屾瑨鍏屽┑顔诲嵆瀹曟洟骞庨挊澶岀枀闂佸綊妫跨粈浣虹不閿濆鐓ラ柡鍐ㄦ储閳ь兘鍋撻梺绋款儐閹歌崵绮悢鐓庣劦妞ゆ帒瀚畵渚€鎮楅敐搴℃灍闁稿鍔栭妵鍕箛閸偆绐旀繝銏ｎ潐濞茬喖寮婚敐鍡樺劅闁靛繆鎳囧Λ銊╂⒑缁嬪尅鏀婚柛銊ョ仢閻ｇ兘骞囬弶璇狙冾熆鐠虹尨榫氱悮婵嬫⒒娓氣偓閳ь剛鍋涢懟顖涙櫠鐎涙ɑ鍙忓┑鐘插暞閵囨繄鈧娲忛崝宥囨崲濠靛宸濇い鎾楀嫷妫ㄩ梻鍌氬€烽懗鍓佸垝椤栫偑鈧啴宕ㄩ鍥ㄧ☉閳规垹鈧綆浜為悿鍥⒑缂佹ɑ鐓ラ柛姘儔瀹曟垿宕熼娑氬幗闂佽宕樺▍鏇㈠箲閿濆棎浜滈柟鎯х摠閵囨繃鎱ㄦ繝鍥╃窗闁靛洦鍔欓獮鎺楀箣濠婂啯鐎抽梻鍌欑閹诧繝骞愮拠鑼殕缂佸娉曢弳锕傛煙閻楀牊绶茬痪顓涘亾闂備胶绮崝蹇涘疾濠婂牞缍栫€广儱顦伴埛鎴︽煕閿旇寮ㄦ俊鐐倐閺屾盯濡歌椤ｈ偐绱掗崒姘毙ｉ柕鍫秮瀹曟﹢鍩為悙顒€顏归梻鍌欑閹诧紕绮欓幋锔芥櫇闁挎洖鍊哥粻鐘绘煛閸モ晛啸缁炬儳銈搁弻銈囧枈閸楃偛顫┑鐐叉噺濞叉鎹㈠☉銏犲耿闁归偊鍓涙导鍫ユ⒑鐠団€虫灍闁荤啿鏅犻獮鍐煥閸忓墽鍠撶槐鎺懳熼悡搴＄紦闂傚倸鍊峰ù鍥敋瑜忛幑銏ゅ箳濡も偓绾惧鏌ｅΟ娆惧殭缁炬儳顭烽弻鐔兼焽閿曗偓楠炴霉?trace闂傚倸鍊搁崐鎼佸磹妞嬪孩顐芥慨姗嗗墻閻掔晫鎲稿鍫罕闂備礁鎼崯鐘诲磻閹惧灈鍋撻崹顐ｇ凡閻庢矮鍗抽獮鍐倷閸濆嫮鍘搁梺鎸庢礀缁岀憖y 闂?step message 濠电姷鏁告慨鐑藉极閹间礁纾婚柣妯款嚙缁犲灚銇勮箛鎾搭棤缂佲偓婵犲洦鐓冪憸婊堝礈濮樿鲸宕叉繛鎴炵懃缁剁偤鎮楅敐搴′簽妞わ缚鍗抽幃妤€鈻撻崹顔界彯闂佺顑呴敃銉︾┍婵犲洦鍤嬮梻鍫熺〒缁愮偞绻?     */
    private static class FieldEvidenceExecutionStats {

        private final int plannedCount;
        private final int executedCount;
        private final int skippedCount;
        private final Map<String, Integer> skipReasons;
        private final long elapsedMillis;

        private FieldEvidenceExecutionStats(int plannedCount,
                                            int executedCount,
                                            int skippedCount,
                                            Map<String, Integer> skipReasons,
                                            long elapsedMillis) {
            this.plannedCount = plannedCount;
            this.executedCount = executedCount;
            this.skippedCount = skippedCount;
            this.skipReasons = skipReasons == null || skipReasons.isEmpty() ? Map.of() : Map.copyOf(skipReasons);
            this.elapsedMillis = elapsedMillis;
        }

        private int getPlannedCount() {
            return plannedCount;
        }

        private int getExecutedCount() {
            return executedCount;
        }

        private int getSkippedCount() {
            return skippedCount;
        }

        private Map<String, Integer> getSkipReasons() {
            return skipReasons;
        }

        private long getElapsedMillis() {
            return elapsedMillis;
        }
    }

    /**
     * 闂傚倸鍊搁崐椋庣矆娓氣偓楠炴牠顢曚綅閸ヮ剦鏁冮柨鏇楀亾闁汇倗鍋撶换婵囩節閸屾粌顤€闂佺顑戠换婵嬪蓟閺囥垹閱囨繝鍨姈绗戦梻浣藉亹椤牏绱炴繝鍥ц摕闁哄洢鍨归柋鍥ㄧ節闂堟稒绁╂俊顐熷墲缁绘繂鈻撻崹顔界亶缂備緡鍠栭惌鍌炲春閻愬搫绠ｉ柨鏃囨娴滃綊鏌ｆ惔鈩冭础濠殿喗鎸宠棢婵犻潧妫岄弨浠嬫煟閹邦剙绾ч柛锝囧厴閺屾稒绻濋崘鐐暭濠碘€冲级閸旀瑩鐛Ο灏栧亾濞戞顏堫敁閹剧粯鐓熼柣鏂挎憸閹冲啴鎮楀鐓庡⒋婵☆偄瀚鍏煎緞鐎Ｑ勫闂佽崵濮村ú鈺冧焊濞嗘垹涓嶉柣妤€鐗勬禍婊堟煥閺囩偛鈧兘骞夋ィ鍐╃厱闁宠鍎虫禍鐐繆閻愵亜鈧牜鏁幒妤€纾圭憸鐗堝笒濮规煡鏌嶉崫鍕偓鑸电濠婂牊鐓忛煫鍥ㄦ礀椤庢捇妫呴澶婂⒋闁哄瞼鍠撶划娆撳箰鎼粹剝鐣婚柣搴ゎ潐濞叉粓宕伴弽顓溾偓浣糕槈濮楀棙鍍垫俊鎻掓湰閻楁洟寮懜鐢电瘈闁汇垽娼у暩闂佽桨绀侀幉锟犲箞閵娾晛绠绘い鏃囧亹閿涙瑦绻濋悽闈浶ｉ柤鐟板⒔缁煤椤忓懐鍘介梺鍝勫€圭€笛囧箟閸濄儳纾?query 闂傚倸鍊搁崐鎼佸磹閻戣姤鍊块柨鏇炲€归崕鎴犳喐閻楀牆绗掔紒鈧径灞稿亾閸忓浜鹃梺閫炲苯澧撮柛鈹惧亾濡炪倖甯婇懗璺衡枔娴煎瓨鐓曢柕澶嬪灥閹冲孩鎱ㄩ敂鎴掔箚闁绘劦浜滈埀顒佺墱閺侇喗绻濋崶銊ユ畱闂佸壊鍋呭ú姗€宕曞Δ鈧埞鎴︽偐閸欏顦╅梺缁樻尵閸犳牠寮婚妸銉㈡斀闁糕剝渚楅埀顒侇殔椤儻顦叉い鏇ㄥ弮閸┾偓妞ゆ帊绶￠崯蹇涙煕閿濆骸娅嶇€规洘鍨垮畷鍗炍熼懖鈺侀獎闂備礁鎼ú銊︽叏椤撱垹纾婚柟鍓х帛閺呮彃顭跨捄渚剱闂佹鍙冨铏规嫚閳ヨ櫕鐏嗙紓浣藉煐閼归箖锝?/ 濠电姷鏁告慨鐑姐€傞挊澹╋綁宕ㄩ弶鎴狅紱闂佸憡渚楅崣搴ㄦ偄閸℃ü绻嗘い鏍ㄦ皑濮ｇ偤鏌涚€ｎ偅灏い顐ｇ箞閹剝鎯旈姀銏犵秵闂傚倷娴囬鏍窗濮樿泛纾婚柟鎹愬煐瀹曞弶绻濋棃娑欙紞婵炲皷鏅滈妵鍕箳閹存繀绨藉┑陇寮撻崡鍐差潖缂佹ɑ濯撮柛娑橈工閺嗗牊绻涢幘瀵割暡妞ゃ劌锕ら悾鐑藉閵堝棗娈愰梺鍐叉惈閸婃悂顢撳☉銏♀拺闁告繂瀚～锕傛煕鎼淬垻鍙€闁诡喒鈧枼鏋庨柟瀵稿Х閿涙粌鈹戞幊閸婃捇鎳楅崼鏇炵叀濠㈣泛顑勭换鍡樸亜閹伴潧浜滅€殿噮鍠氶埀?/ 闂傚倸鍊搁崐鎼佸磹閻戣姤鍊块柨鏇炲€哥粻鏍煕椤愶絾绀€缁炬儳娼￠弻褑绠涢敐鍛敖缂備胶濞€缁犳牠寮诲鍫闂佸憡鎸诲畝鍛婁繆閻㈢绀嬫い鏍ㄧ⊕濞呮粓姊虹粙鎸庢拱缂佸甯￠幃鐢稿冀椤愩倗锛濇繛鎾磋壘濞层倝寮搁敂濮愪簻闁哄洤妫楀Λ娑欑濞嗘挻鈷掑ù锝呮啞閸熺偞绻涚拠褏鐣电€规洏鍨虹缓鐣岀矙鐠侯煈妲锋繝鐢靛Т閿曘倝鎮ф繝鍥ㄥ亗婵炴垶锕╅悢鍡涙偣閾忕懓鍔嬫い銉у仦缁绘盯宕ㄩ鐔锋缂備胶绮惄顖炲箠閻樻椿鏁嗛柍褜鍓熼崺銏ゅ醇閵夛妇鍘遍梺瀹狀潐閸庤櫕绂嶉悙顑跨箚闁绘劦浜滈埀顒佺墱閺侇喗绻濋崶銊ユ畱闂佸憡鎸烽懗鑸电▔瀹ュ鐓欓悗鐢殿焾鍟哥紒鐐劤閸氬绌辨繝鍥ㄥ€烽柡澶嬪灩椤︺劑姊虹粙璺ㄧ闁兼椿鍨跺﹢渚€姊虹紒妯兼噧闁硅櫕鍔欓幃楣冩偨绾版ê浜鹃悷娆忓缁€鍐偨椤栨稑娴柛鈹垮劜瀵板嫭绻涢悙顒傗偓濠氭煟韫囨洖浠滃褑濮ょ粋宥呪枎閹剧补鎷洪柣鐘叉处瑜板啴顢楅姀掳浜滈柡鍐ｅ亾闁绘濮撮悾閿嬪閺夋垵鍞ㄥ銈嗗姧缁茶姤鎯旀繝鍥ㄢ拺闁革富鍘奸。鍏肩節閵忊槄鑰跨€规洖缍婂畷鎺楁倷閼碱剦鍟囩紓鍌欑贰閸犳盯锝炴径鎰剁稏濠电姴娲﹂悡鏇㈡煏閸繃顥炵紒鈧€ｎ喗鐓涢悘鐐垫櫕鍟稿銇卞倻绐旈柡灞剧洴楠炴鎹勯悜妯间邯闁?     * coordinator闂傚倸鍊搁崐鎼佸磹妞嬪孩顐芥慨姗嗗墻閻掔晫鎲稿鍫罕闂備礁鎼崯鐘诲磻閹惧绠鹃柣鎾虫捣缁犱即鏌曢崱妯烘诞闁诡喗顨嗙换鍛村Υ閸℃稒鈷掗柛灞捐壘閳ь剟顥撳▎銏狀潩椤掑鍔烽悷婊冪Х閻忓姊洪崫鍕殜闁稿鎸鹃埀顒€鐏氬妯尖偓姘嵆楠炲啴鎮欓崫鍕幐闂佹寧娲栫粚鐟€y 闂?provider request 闂傚倸鍊搁崐鎼佸磹閹间礁纾瑰瀣椤愪粙鏌ㄩ悢鍝勑㈢紒鎰殕缁绘盯骞嬮悜鍡欏姱濠电偞鍨崹鍦矆鐎ｎ偁浜滈柡宥冨€曟禍楣冩⒑閹肩偛鍔撮柛鎾寸懇閹锋垿鎮㈤崗鑲╁帾婵犮垼鍩栫粙鎾绘偩閸楃伝褰掓偐閾忣偁浠㈠┑顔硷攻濡炶棄螞閸愩劉妲堥弶鍫氭櫅婵ジ姊绘担渚劸閻庢稈鏅滅换娑欑節閸屻倕娈ㄩ梺鎯ф禋閸嬪棛寮ч埀顒勬⒑閹肩偛鍔橀柛鏂块叄瀹曪綁宕卞缁樻杸闂佺粯鍔栧娆撴倶閿斿浜滄い鎾跺仦閸犳﹢鏌熼鎯т户缂侇喗鐟ヨ灋闂傚牊绋掔拹鈥趁瑰鍕€愮€殿喕绮欐俊鎼佹晜閹呯Р闂備浇顕у锕傦綖婢跺⊕鍝勵潨閳ь剟濡撮崘顔煎窛闁圭⒈鍘介弲锝嗙節闂堟稑鈧鈥﹂崼婵愬晠婵犻潧娲㈡禍婊堟煛閸屾繃纭舵慨锝囧仱閺屾稓鈧綆鍋呯亸顓㈡懚閺嶎厽鐓冪憸婊堝礈閻旂厧鐏抽柨鏃傚亾瀹曞鏌曟繝蹇曠暠缁剧虎鍨跺娲川婵犲嫮鐣甸柣搴㈠哺閺€杈╃矉閹烘挾闄勯柛娑樑堥幏娲⒑閸涘﹦缂氶柛搴ㄤ憾瀵娊鏌嗗鍡欏幈闁诲函缍嗛崑鍛暦鐏炵偓鍙忓┑鐘插暞閵囨繈鏌熼鐟板⒉闁诡垱妫冮、娆撴偩鐏炲墽鈧喗绻濋悽闈涗粶闁宦板妿閸掓帡鏁愰崶鈺冾槸婵炴挻鍩冮崑鎾斥攽閳ュ磭鎽犵紒妤冨枛閸┾偓妞ゆ帒瀚畵渚€鎮楅敐搴℃灍闁哄懏绻堥弻宥堫檨闁告挻绋撻崚鎺撶節濮橆剛顔呴梺鍏间航閸庢娊宕㈤鍛瘈闁汇垽娼ф禒婊堟煟韫囨梻绠炵€规洘绻堥獮姗€顢欓悾灞藉箞闂備線娼ч悧鍡涘疮椤愶箑鐒垫い鎺嶈兌缁犵偞顨ラ悙鎻掓殭閾绘牕霉閿濆洦鍤€妞ゅ孩鎹囧娲川婵犲倸袝闂佺粯鎸搁悧鍡氱亱闂佸壊鍋侀崕鏌ュ煕閹达附鐓欓柤娴嬫櫅娴犳帞绱掓潏顭掕€块柡灞剧洴婵＄兘骞嬪┑鍥ф闂佸憡锕╅崜鐔奉潖缂佹ɑ濯撮柣鐔煎亰閸ゅ绱撴担绛嬪殭闁稿﹤娼￠悰顔界節閸パ呯杸濡炪倖鏌ㄦ晶浠嬬嵁閹剧粯鈷戠紓浣姑慨鍥ㄣ亜閺囥劌骞樼€殿啫鍥х劦妞ゆ帒瀚埛鎴︽煕濠靛棗顏€瑰憡绻堥弻娑氣偓锝庡亞濞叉挳鏌涢埞鎯т壕婵＄偑鍊栫敮鎺楀窗濮樿泛鑸归柧蹇撴贡绾惧ジ鏌曟繝蹇涙闁靛洦绻冮幈?     */
    private record InitialCandidateResolution(List<SourceCandidate> candidates,
                                              List<SourceCandidate> rejectedCandidates) {
    }

    private static class ResolvedFieldEvidenceQueryPlan {

        private final List<FieldEvidenceQuery> planned;
        private final List<FieldEvidenceQuery> executable;
        private final List<FieldEvidenceQuery> skipped;
        private final Map<String, Integer> skipReasons;

        private ResolvedFieldEvidenceQueryPlan(List<FieldEvidenceQuery> planned,
                                               List<FieldEvidenceQuery> executable,
                                               List<FieldEvidenceQuery> skipped,
                                               Map<String, Integer> skipReasons) {
            this.planned = planned == null ? List.of() : List.copyOf(planned);
            this.executable = executable == null ? List.of() : List.copyOf(executable);
            this.skipped = skipped == null ? List.of() : List.copyOf(skipped);
            this.skipReasons = skipReasons == null || skipReasons.isEmpty() ? Map.of() : Map.copyOf(skipReasons);
        }

        private static ResolvedFieldEvidenceQueryPlan empty() {
            return new ResolvedFieldEvidenceQueryPlan(List.of(), List.of(), List.of(), Map.of());
        }

        private static ResolvedFieldEvidenceQueryPlan from(FieldEvidenceQueryExecutionPlan plan) {
            if (plan == null) {
                return empty();
            }
            return new ResolvedFieldEvidenceQueryPlan(
                    plan.getPlanned(),
                    plan.getExecutable(),
                    plan.getSkipped(),
                    plan.getSkipReasons()
            );
        }

        private List<FieldEvidenceQuery> getPlanned() {
            return planned;
        }

        private List<FieldEvidenceQuery> getExecutable() {
            return executable;
        }

        private List<FieldEvidenceQuery> getSkipped() {
            return skipped;
        }

        private Map<String, Integer> getSkipReasons() {
            return skipReasons;
        }
    }
}

class SourceDiagnostic {
  const SourceDiagnostic._(this.stage, this.site, this.kind, this.cause, this.archive, this.initCause, this.nativeOperation, this.initNativeOperation);

  final String stage;
  final String site;
  final String kind;
  final String cause;
  final String archive;
  final String initCause;
  final String nativeOperation;
  final String initNativeOperation;

  static SourceDiagnostic? tryParse(Object? json) {
    if (json is! Map<String, dynamic>) return null;
    final stage = json['stage'];
    final kind = json['kind'];
    if (stage is! String || kind is! String || !_identifier.hasMatch(stage) || !_identifier.hasMatch(kind)) return null;
    final cause = _safeCause(json['cause']);
    if (cause.isEmpty) return null;
    final site = json['site'];
    final archive = json['archive'];
    return SourceDiagnostic._(
      stage,
      publicSite(site),
      kind,
      cause,
      archive is String && _archiveId.hasMatch(archive) ? archive : '',
      _safeCause(json['initCause']),
      _safeNativeOperation(json['nativeOperation']),
      _safeNativeOperation(json['initNativeOperation']),
    );
  }

  static final _identifier = RegExp(r'^[a-z][a-z._]{0,47}$');
  static final _archiveId = RegExp(r'^[a-fA-F0-9]{12}$');
  static final _siteId = RegExp(r'^[\p{L}\p{N}_.-]{1,64}$', unicode: true);
  static String publicSite(Object? site) => site is String && _siteId.hasMatch(site) ? site : '';
  static final _exception = RegExp(r'^([A-Za-z_$][A-Za-z0-9_$]{0,75}(?:Error|Exception)|Error|Exception|Throwable)(?:$|\b)');
  static final _javaSymbol = RegExp(r'^(?:android|androidx|dalvik|java|com\.github\.catvod)(?:\.[A-Za-z_$][A-Za-z0-9_$]*)+$');
  static final _httpCode = RegExp(r'^HTTP [1-5]\d\d$');
  static final _nativeSymbol = RegExp(r'^(?:JNI method|JNI callback) (?:[A-Za-z0-9_$]+/)*[A-Za-z0-9_$]+->(?:[A-Za-z_$][A-Za-z0-9_$]*|<init>)(?:\([A-Za-z0-9_$/;\[]*\)|:)[A-Za-z0-9_$/;\[]+$');

  static String _safeNativeOperation(Object? value) =>
      value is String && value.length <= 240 && _nativeSymbol.firstMatch(value)?.end == value.length ? value : '';
  static const _nativeReasons = {
    'Android JNI: unsupported signature': '原生方法的参数或返回类型尚不支持',
    'Android JNI: guest timed out': 'Android 原生代码执行超时',
    'Android JNI: guest unavailable': 'Android 原生运行环境不可用',
    'Android JNI: native call failed': 'Android 原生方法调用失败',
    'Android JNI: unsupported Java callback': 'Android 原生代码需要的 Java 回调尚不支持',
    'Android JNI: Java callback failed': 'Android 原生代码发起的 Java 回调执行失败',
    'Android JNI: guest nesting limit': 'Android 原生调用超过嵌套层数上限',
    'Android JNI: library path inaccessible': '无法访问 Android 原生库路径',
    'Android JNI: guest initialization failed': 'Android 原生运行环境初始化失败',
    'Android JNI: native dependency missing': '缺少原生库依赖',
    'Android JNI: native symbols unresolved': '找不到原生库引用的符号',
    'Android JNI: native method not bound': '找不到该 JNI 方法的绑定',
    'Android JNI: JNI_OnLoad failed': '原生库在 JNI_OnLoad 初始化时失败',
  };
  static const _hints = {
    'armeabi-v7a',
    'arm64-v8a',
    'x86',
    'x86_64',
    'killall',
    'nohup',
    'chmod',
    'sh',
    'detail is empty',
    'site returned invalid JSON',
    'site returned non-object JSON',
    'site returned no play url',
    'empty quality list',
    'spider returned no play info',
    'response does not contain a JAR',
    'empty JAR response',
    'invalid subscription document',
    'subscription returned a web page',
    'empty subscription response',
    'invalid subscription address',
    'repository has no entries',
    'repository lists itself',
    'repositories nested too deep',
  };

  static String _safeCause(Object? value) {
    if (value is! String || value.isEmpty) return '';
    if (value == 'Missing JAR') return value;
    final type = _exception.firstMatch(value)?.group(1);
    if (type == null) return '未识别的异常';
    if (!value.startsWith('$type: ') || value.length > 210) return type;
    final hint = value.substring(type.length + 2);
    return _hints.contains(hint) || _nativeReasons.containsKey(hint) || _httpCode.hasMatch(hint) || _javaSymbol.hasMatch(hint) ? '$type: $hint' : type;
  }

  String get stageLabel =>
      const {
        'config.fetch': '获取订阅',
        'config.parse': '解析订阅配置',
        'jar.fetch': '下载蜘蛛包',
        'jar.load': '加载蜘蛛包',
        'jar.init': '初始化共享蜘蛛包',
        'spider.create': '创建站点蜘蛛',
        'spider.init': '初始化站点蜘蛛',
        'spider.home': '读取首页',
        'spider.recommend': '读取首页推荐',
        'spider.category': '读取分类',
        'spider.detail': '读取详情',
        'spider.search': '搜索站点',
        'spider.play': '解析播放地址',
        'spider.live': '读取直播',
        'spider.proxy': '调用蜘蛛代理',
      }[stage] ??
      stage;

  String get kindLabel {
    if (kind == 'native') {
      const prefix = 'UnsatisfiedLinkError: ';
      final nativeCause = _sharedInitWrapper ? initCause : cause;
      if (nativeCause.startsWith(prefix)) {
        final label = _nativeReasons[nativeCause.substring(prefix.length)];
        if (label != null) return _sharedInitWrapper ? '共享蜘蛛包：$label' : label;
      }
    }
    if (kind == 'network') {
      if (cause == 'UnknownHostException') return '域名解析失败（DNS）';
      if (cause == 'SocketTimeoutException') return '网络请求超时';
      final hint = cause.split(': ').last;
      if (_httpCode.hasMatch(hint)) return '上游返回 $hint';
    }
    if (stage == 'config.parse' && kind == 'invalid_result') {
      if (cause.endsWith(': subscription returned a web page')) return '订阅地址返回了网页';
      if (cause.endsWith(': empty subscription response')) return '订阅内容为空';
      return '订阅配置无效';
    }
    return const {
          'native': '原生库调用失败',
          'missing_spider': '配置指定的蜘蛛类缺失',
          'missing_class': '找不到所需的类',
          'missing_member': '找不到所需的字段或方法',
          'command': '外部命令执行失败',
          'network': '网络请求失败',
          'invalid_result': '返回的数据无法读取',
          'other': '蜘蛛执行异常',
        }[kind] ??
        '请求失败';
  }

  String get summary => '$stageLabel · $kindLabel';

  bool get _sharedInitWrapper => stage.startsWith('spider.') && initCause.isNotEmpty &&
      (cause == 'ExceptionInInitializerError' || cause == 'UnsatisfiedLinkError: Android JNI: guest unavailable');

  Map<String, String> get details => {
    if (site.isNotEmpty) '站点': site,
    '阶段': '$stageLabel ($stage)',
    '类别': '$kindLabel ($kind)',
    (_sharedInitWrapper ? '站点异常' : '根因'): cause,
    if (nativeOperation.isNotEmpty) 'JNI 调用': nativeOperation,
    if (archive.isNotEmpty) '蜘蛛包缓存标识': archive,
    if (initCause.isNotEmpty) '共享包首次初始化': initCause,
    if (initNativeOperation.isNotEmpty) '共享包首次失败的 JNI 调用': initNativeOperation,
  };

  String get report => details.entries.map((entry) => '${entry.key}：${entry.value}').join('\n');
}

import 'dart:math' as math;

import 'package:flutter/foundation.dart' show ValueListenable;
import 'package:material_ui/material_ui.dart';

import '../app_state.dart';
import '../diagnostics/diagnostics_page.dart' show SourceReportButton;
import '../engine/engine_client.dart';
import '../engine/models.dart';
import '../engine/vod_models.dart';
import '../ui/common.dart';
import '../ui/device_form.dart';
import '../ui/poster.dart';
import '../ui/tv/tv_focus.dart';
import '../ui/tv/tv_widgets.dart';
import 'detail_page.dart';
import 'home_switcher.dart';
import 'simple_home.dart';

/// 首页：current site's recommendations and categories.
///
/// In simple mode ([simple]) it starts on 继续看 (what was being watched, and the favourites: simple_home.dart), the
/// site's recommendations and categories follow as the next tabs, and the header has one button with the site's
/// name that opens a panel from the right: the sites, and a step up to the entries and subscriptions.
class BrowsePage extends StatefulWidget {
  const BrowsePage({super.key, required this.onOpenSources, this.simple = false});

  final VoidCallback onOpenSources;
  final bool simple;

  @override
  State<BrowsePage> createState() => _BrowsePageState();
}

class _BrowsePageState extends State<BrowsePage> {
  /// The refresh of the site on screen, once its home has loaded; the header's button uses it.
  final _refresh = _RefreshSlot();

  @override
  void dispose() {
    _refresh.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final onOpenSources = widget.onOpenSources;
    final state = AppScope.of(context);
    final site = state.currentSite;
    final Widget body;
    if (!state.connected) {
      body = state.startingEngine
          ? const Center(
              child: Column(mainAxisSize: MainAxisSize.min, children: [CircularProgressIndicator(), SizedBox(height: 16), Text('正在启动内置引擎…')]),
            )
          : StatusView(icon: Icons.link_off, message: state.error ?? '还没有连接引擎', actionLabel: '去设置', onAction: onOpenSources);
    } else if (!state.hasConfig && state.loadingConfig) {
      body = const Center(
        child: Column(mainAxisSize: MainAxisSize.min, children: [CircularProgressIndicator(), SizedBox(height: 16), Text('正在加载订阅…')]),
      );
    } else if (!state.hasConfig && state.configError != null) {
      final issue = state.configDiagnostic;
      body = StatusView(icon: Icons.cloud_off, message: '订阅加载失败\n${state.configError}', actionLabel: '重试', onAction: state.reloadConfig, extraAction: issue == null ? null : SourceReportButton(issues: [issue]));
    } else if (!state.hasConfig) {
      body = StatusView(icon: Icons.playlist_add, message: '还没有导入订阅', actionLabel: '导入订阅', onAction: onOpenSources);
    } else if (site == null) {
      body = StatusView(icon: Icons.block, message: '当前订阅里没有这台引擎能打开的站点\n（Python 站点要先在「设置」页设置 Python）', actionLabel: '查看站点', onAction: onOpenSources);
    } else {
      // Keyed on the config too: another subscription or entry with a site of the same key starts afresh.
      body = _SiteBrowser(key: ValueKey('${state.configVersion}|${site.key}|${widget.simple}'), site: site, refresh: _refresh, simple: widget.simple);
    }
    // The header (where the content comes from, and refresh) stays while the content below changes, so a switch
    // keeps the TV's focus on it; it is there whenever a config is, also one without a site to show.
    return Scaffold(
      body: SafeArea(
        child: state.connected && state.hasConfig
            ? Column(children: [_Header(refresh: _refresh, simple: widget.simple), if (state.busy) const LinearProgressIndicator(minHeight: 2), Expanded(child: body)])
            : body,
      ),
    );
  }
}

/// The site's refresh for the header; knows when it is gone, for callbacks that run after the page.
class _RefreshSlot extends ValueNotifier<VoidCallback?> {
  _RefreshSlot() : super(null);

  bool disposed = false;

  @override
  void dispose() {
    disposed = true;
    super.dispose();
  }
}

class _SiteBrowser extends StatefulWidget {
  const _SiteBrowser({super.key, required this.site, required this.refresh, this.simple = false});

  final SiteInfo site;

  /// Simple mode: 继续看 comes first, and the categories have no filters.
  final bool simple;

  /// Set to this page's refresh while its home is loaded, so the header's button can ask the site again.
  final _RefreshSlot refresh;

  @override
  State<_SiteBrowser> createState() => _SiteBrowserState();
}

class _SiteBrowserState extends State<_SiteBrowser> {
  /// The site's home, and (simple mode) whether anything was watched or kept: 继续看 is where the page opens then.
  late Future<(HomeResult, bool)> _home;

  @override
  void initState() {
    super.initState();
    _load();
  }

  /// Counts the refreshes asked for: the categories are built anew, and ask the site instead of the engine's cache.
  int _refreshes = 0;

  void _load({bool refresh = false}) {
    final client = AppScope.read(context).client;
    final library = widget.simple ? _watchedAnything(client) : Future.value(false);
    _home = client.home(widget.site.key, refresh: refresh).then((home) async => (home, await library));
    _home.ignore();
  }

  static Future<bool> _watchedAnything(EngineClient client) async {
    try {
      if ((await client.history(limit: 1)).isNotEmpty) return true;
      return (await client.favorites()).isNotEmpty;
    } catch (_) {
      return false;
    }
  }

  void _refresh() => setState(() {
    _refreshes++;
    _load(refresh: true);
  });

  /// Offers [_refresh] to the header when the home has loaded, after this frame (not while building).
  void _offerRefresh(bool loaded) {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted || widget.refresh.disposed) return;
      final wanted = loaded ? _refresh : null;
      if (widget.refresh.value != wanted) widget.refresh.value = wanted;
    });
  }

  @override
  void dispose() {
    final refresh = widget.refresh;
    final mine = _refresh;
    // Only take it back if it is still this page's (the next page may have offered its own already).
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!refresh.disposed && refresh.value == mine) refresh.value = null;
    });
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return FutureBuilder<(HomeResult, bool)>(
      future: _home,
      builder: (context, snap) {
        _offerRefresh(snap.connectionState == ConnectionState.done);
        if (snap.connectionState != ConnectionState.done) {
          return const LoadingView();
        }
        if (snap.hasError) {
          return StatusView(
            icon: Icons.cloud_off,
            message: '${widget.site.name} 加载失败\n${errorText(snap.error)}',
            actionLabel: '重试',
            onAction: () => setState(_load),
            extraAction: sourceReportFor(snap.error),
          );
        }
        final (home, watched) = snap.data!;
        final simple = widget.simple;
        final hasRecommend = home.list.isNotEmpty;
        final tabs = [if (simple) '继续看', if (hasRecommend) '推荐', for (final c in home.classes) c.name];
        if (tabs.isEmpty) {
          if (home.warning case final issue?) {
            return StatusView(icon: Icons.warning_amber_rounded, message: issue.summary, extraAction: SourceReportButton(issues: [issue]));
          }
          return const StatusView(icon: Icons.inbox, message: '这个站点没有返回分类');
        }
        return DefaultTabController(
          length: tabs.length,
          // Nothing watched yet: what the site recommends is the better start.
          initialIndex: simple && !watched && tabs.length > 1 ? 1 : 0,
          child: Column(
            children: [
              if (home.warning case final issue?)
                Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 12),
                  child: Row(children: [Expanded(child: Text(issue.summary)), SourceReportButton(issues: [issue])]),
                ),
              Align(
                alignment: Alignment.centerLeft,
                child: Builder(
                  builder: (context) => TabBar(isScrollable: true, onFocusChange: tvTabFollowsFocus(context), tabs: [for (final t in tabs) Tab(text: t, height: 40)]),
                ),
              ),
              Expanded(
                child: TabBarView(
                  children: [
                    if (simple) const SimpleHomeTab(),
                    if (hasRecommend) _VodGrid.fixed(items: home.list),
                    for (final c in home.classes)
                      _CategoryView(
                        key: ValueKey('${c.id}#$_refreshes'),
                        site: widget.site.key,
                        classId: c.id,
                        filters: simple ? const [] : home.filters[c.id] ?? const [],
                        refresh: _refreshes > 0,
                      ),
                  ],
                ),
              ),
            ],
          ),
        );
      },
    );
  }
}

class _Header extends StatelessWidget {
  const _Header({required this.refresh, this.simple = false});

  /// The site's refresh, or null while its home is loading.
  final ValueListenable<VoidCallback?> refresh;

  /// Simple mode: the site is named, not switched.
  final bool simple;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final site = AppScope.of(context).currentSite;
    return Padding(
      padding: const EdgeInsets.fromLTRB(20, 14, 12, 4),
      child: Row(
        children: [
          Text('首页', style: theme.textTheme.headlineSmall?.copyWith(fontWeight: FontWeight.w700)),
          const SizedBox(width: 16),
          if (simple)
            Flexible(child: Align(alignment: Alignment.centerLeft, child: SourcePanelChip(siteName: site?.name ?? '选择站点')))
          else
            const Flexible(child: HomeSwitcher()),
          const SizedBox(width: 4),
          ValueListenableBuilder<VoidCallback?>(
            valueListenable: refresh,
            builder: (context, onRefresh, _) =>
                IconButton(key: const Key('home-refresh'), tooltip: '刷新（重新向站点获取）', icon: const Icon(Icons.refresh), onPressed: onRefresh),
          ),
        ],
      ),
    );
  }
}

/// A category: filter bar + paged poster wall that loads more as you scroll.
/// What a folder card (`vod_tag: folder`) holds: the site's category whose id is the card's id, as TVBox and FongMi
/// open it. Folders inside open the next level; back goes up one.
class FolderPage extends StatelessWidget {
  const FolderPage({super.key, required this.folder});

  final VodCard folder;

  @override
  Widget build(BuildContext context) => Scaffold(
        appBar: AppBar(title: Text(folder.name, key: const Key('folder-title'), overflow: TextOverflow.ellipsis)),
        body: _CategoryView(site: folder.site, classId: folder.id, filters: const [], autofocusFirst: isTv(context)),
      );
}

class _CategoryView extends StatefulWidget {
  const _CategoryView({super.key, required this.site, required this.classId, required this.filters, this.refresh = false, this.autofocusFirst = false});

  final String site;
  final String classId;
  final List<VodFilter> filters;

  /// The first poster takes the focus once it is there (a page of its own on a TV).
  final bool autofocusFirst;

  /// The user asked for fresh data: pages come from the site, not from what the engine kept.
  final bool refresh;

  @override
  State<_CategoryView> createState() => _CategoryViewState();
}

class _CategoryViewState extends State<_CategoryView> with AutomaticKeepAliveClientMixin {
  final _items = <VodCard>[];
  final _selected = <String, String>{};
  int _page = 0;
  bool _hasMore = true;
  bool _loading = false;
  bool _showFilters = false;
  Object? _error;
  int _generation = 0;

  @override
  bool get wantKeepAlive => true;

  @override
  void initState() {
    super.initState();
    _loadMore();
  }

  Future<void> _loadMore() async {
    if (_loading || !_hasMore) return;
    final generation = _generation;
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final page = await AppScope.read(context).client.category(
        widget.site,
        widget.classId,
        page: _page + 1,
        refresh: widget.refresh,
        filters: {
          for (final e in _selected.entries)
            if (e.value.isNotEmpty) e.key: e.value,
        },
      );
      if (!mounted || generation != _generation) return;
      setState(() {
        _page += 1;
        _items.addAll(page.list);
        _hasMore = page.hasMore && page.list.isNotEmpty;
      });
    } catch (e) {
      if (mounted && generation == _generation) setState(() => _error = e);
    } finally {
      if (mounted && generation == _generation) setState(() => _loading = false);
    }
  }

  void _setFilter(String key, String value) {
    setState(() {
      _selected[key] = value;
      _generation++;
      _items.clear();
      _page = 0;
      _hasMore = true;
      _loading = false;
    });
    _loadMore();
  }

  @override
  Widget build(BuildContext context) {
    super.build(context);
    return LayoutBuilder(
      builder: (context, constraints) => Column(
        children: [
          if (widget.filters.isNotEmpty)
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 4, 16, 0),
              child: Row(
                children: [
                  TextButton.icon(
                    onPressed: () => setState(() => _showFilters = !_showFilters),
                    icon: Icon(_showFilters ? Icons.expand_less : Icons.tune, size: 18),
                    label: Text(_showFilters ? '收起筛选' : '筛选'),
                  ),
                  if (_selected.values.any((v) => v.isNotEmpty))
                    Expanded(
                      child: Text(
                        _summary(),
                        overflow: TextOverflow.ellipsis,
                        style: TextStyle(color: Theme.of(context).colorScheme.primary, fontSize: 13),
                      ),
                    ),
                ],
              ),
            ),
          AnimatedSize(
            duration: const Duration(milliseconds: 180),
            alignment: Alignment.topCenter,
            // Some sites have a dozen rows of filters. They get at most half the height and scroll inside it, so
            // the posters keep their room and, on a TV, the row with the focus is always on the screen.
            child: _showFilters
                ? ConstrainedBox(
                    constraints: BoxConstraints(maxHeight: constraints.hasBoundedHeight ? math.max(96, constraints.maxHeight * 0.5) : 240),
                    child: SingleChildScrollView(
                      key: const Key('category-filters'),
                      child: _FilterBar(filters: widget.filters, selected: _selected, onSelect: _setFilter),
                    ),
                  )
                : const SizedBox(width: double.infinity),
          ),
          Expanded(
            // On a TV the screen is short: once the focus goes down into the posters the filters fold away (the
            // line next to 筛选 keeps saying what is chosen), and ↑ comes back to 筛选.
            child: Focus(
              canRequestFocus: false,
              skipTraversal: true,
              onFocusChange: (inPosters) {
                if (inPosters && _showFilters && isTv(context)) setState(() => _showFilters = false);
              },
              child: _VodGrid(items: _items, loading: _loading, error: _error, hasMore: _hasMore, onLoadMore: _loadMore, autofocusFirst: widget.autofocusFirst),
            ),
          ),
        ],
      ),
    );
  }

  String _summary() {
    final parts = <String>[];
    for (final f in widget.filters) {
      final v = _selected[f.key];
      if (v == null || v.isEmpty) continue;
      parts.add(
        f.values
            .firstWhere(
              (x) => x.value == v,
              orElse: () => FilterValue(name: v, value: v),
            )
            .name,
      );
    }
    return parts.join(' · ');
  }
}

class _FilterBar extends StatelessWidget {
  const _FilterBar({required this.filters, required this.selected, required this.onSelect});

  final List<VodFilter> filters;
  final Map<String, String> selected;
  final void Function(String key, String value) onSelect;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Padding(
      padding: const EdgeInsets.fromLTRB(20, 0, 20, 8),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          for (final f in filters)
            Padding(
              padding: const EdgeInsets.symmetric(vertical: 3),
              child: Row(
                children: [
                  SizedBox(
                    width: 48,
                    child: Text(f.name, style: TextStyle(color: scheme.onSurfaceVariant, fontSize: 13)),
                  ),
                  Expanded(
                    child: SingleChildScrollView(
                      scrollDirection: Axis.horizontal,
                      child: Row(
                        children: [
                          for (final v in f.values)
                            Padding(
                              padding: const EdgeInsets.only(right: 6),
                              child: ChoiceChip(
                                label: Text(v.name),
                                visualDensity: VisualDensity.compact,
                                selected: (selected[f.key] ?? (f.values.isNotEmpty ? f.values.first.value : '')) == v.value,
                                onSelected: (_) => onSelect(f.key, v.value),
                              ),
                            ),
                        ],
                      ),
                    ),
                  ),
                ],
              ),
            ),
        ],
      ),
    );
  }
}

/// Poster wall. With [onLoadMore] it pages as the user nears the end.
class _VodGrid extends StatefulWidget {
  const _VodGrid({required this.items, this.loading = false, this.error, this.hasMore = false, this.onLoadMore, this.autofocusFirst = false});

  const _VodGrid.fixed({required List<VodCard> items}) : this(items: items);

  final List<VodCard> items;
  final bool loading;
  final Object? error;
  final bool hasMore;
  final VoidCallback? onLoadMore;
  final bool autofocusFirst;

  @override
  State<_VodGrid> createState() => _VodGridState();
}

class _VodGridState extends State<_VodGrid> {
  final _scroll = ScrollController();

  @override
  void initState() {
    super.initState();
    _scroll.addListener(() {
      if (_scroll.position.extentAfter < 600) widget.onLoadMore?.call();
    });
  }

  @override
  void didUpdateWidget(covariant _VodGrid oldWidget) {
    super.didUpdateWidget(oldWidget);
    // A short first page may not fill the screen; keep loading until it scrolls.
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted && _scroll.hasClients && _scroll.position.maxScrollExtent == 0 && widget.hasMore && widget.error == null) {
        widget.onLoadMore?.call();
      }
    });
  }

  @override
  void dispose() {
    _scroll.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    if (widget.items.isEmpty) {
      if (widget.error != null) {
        return StatusView(icon: Icons.cloud_off, message: '加载失败\n${errorText(widget.error)}', actionLabel: '重试', onAction: widget.onLoadMore, extraAction: sourceReportFor(widget.error));
      }
      if (widget.loading) return const LoadingView();
      return const StatusView(icon: Icons.inbox_outlined, message: '这里还没有内容');
    }
    return CustomScrollView(
      controller: _scroll,
      slivers: [
        SliverPadding(
          padding: const EdgeInsets.fromLTRB(20, 12, 20, 12),
          sliver: SliverGrid.builder(
            gridDelegate: posterGridDelegate,
            itemCount: widget.items.length,
            itemBuilder: (context, i) {
              final vod = widget.items[i];
              final poster = PosterCard(vod: vod, onTap: () => openDetail(context, vod));
              // The page's back button has the focus by the time the posters arrive; the first poster takes it.
              return widget.autofocusFirst && i == 0 ? TvAutofocus(child: poster) : poster;
            },
          ),
        ),
        SliverToBoxAdapter(
          child: Padding(
            padding: const EdgeInsets.only(bottom: 24),
            child: Center(
              child: widget.loading
                  ? const SizedBox(width: 22, height: 22, child: CircularProgressIndicator(strokeWidth: 2))
                  : widget.error != null
                  ? TextButton(onPressed: widget.onLoadMore, child: const Text('加载失败，点此重试'))
                  : widget.onLoadMore != null && !widget.hasMore
                  ? Text('没有更多了', style: TextStyle(color: Theme.of(context).colorScheme.onSurfaceVariant, fontSize: 12))
                  : const SizedBox.shrink(),
            ),
          ),
        ),
      ],
    );
  }
}

String errorText(Object? error) => error is EngineException ? error.diagnostic?.summary ?? error.message : '$error';

Widget? sourceReportFor(Object? error) => switch (error) {
  EngineException(diagnostic: final issue?) => SourceReportButton(issues: [issue]),
  _ => null,
};

import 'dart:async';

import 'package:material_ui/material_ui.dart';

import '../engine/vod_models.dart';
import '../ui/phone_input.dart';

class DriveSignInDialog extends StatefulWidget {
  const DriveSignInDialog({super.key, required this.prompt, required this.poll});

  final DriveSignInPrompt prompt;
  final Future<String> Function() poll;

  @override
  State<DriveSignInDialog> createState() => _DriveSignInDialogState();
}

class _DriveSignInDialogState extends State<DriveSignInDialog> {
  final _clock = Stopwatch()..start();
  Timer? _timer;
  String? _error;

  @override
  void initState() {
    super.initState();
    _schedule();
  }

  void _schedule() => _timer = Timer(const Duration(seconds: 2), _poll);

  Future<void> _poll() async {
    String status;
    try {
      status = await widget.poll().timeout(const Duration(seconds: 8));
    } catch (_) {
      status = 'waiting';
    }
    if (!mounted) return;
    if (status == 'success') {
      Navigator.of(context).pop(true);
    } else if (status == 'expired' || _clock.elapsed >= const Duration(minutes: 2)) {
      setState(() => _error = '二维码已过期，请返回后重试当前剧集。');
    } else if (status == 'error') {
      setState(() => _error = '站点未能完成授权，请返回后重试当前剧集。');
    } else {
      _schedule();
    }
  }

  @override
  void dispose() {
    _timer?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => AlertDialog(
        title: const Text('网盘扫码授权'),
        content: _error != null
            ? Text(_error!)
            : Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  if (widget.prompt.image case final image?)
                    Image.network(image, width: 220, height: 220,
                        errorBuilder: (_, _, _) => const Text('无法载入站点提供的二维码。'))
                  else
                    QrView(data: widget.prompt.qrUrl),
                  const SizedBox(height: 12),
                  const Text('请用手机扫描站点提供的二维码。授权成功后会重试当前剧集。'),
                ],
              ),
        actions: [TextButton(onPressed: () => Navigator.of(context).pop(false), child: const Text('关闭'))],
      );
}

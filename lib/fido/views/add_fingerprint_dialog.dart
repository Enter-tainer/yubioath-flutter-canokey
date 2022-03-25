import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:logging/logging.dart';

import '../../app/message.dart';
import '../state.dart';
import '../../app/views/responsive_dialog.dart';
import '../../fido/models.dart';
import '../../app/models.dart';
import '../../app/state.dart';

final _log = Logger('fido.views.add_fingerprint_dialog');

class AddFingerprintDialog extends ConsumerStatefulWidget {
  final DeviceNode node;
  const AddFingerprintDialog(this.node, {Key? key}) : super(key: key);

  @override
  ConsumerState<ConsumerStatefulWidget> createState() =>
      _AddFingerprintDialogState();
}

class _AddFingerprintDialogState extends ConsumerState<AddFingerprintDialog>
    with SingleTickerProviderStateMixin {
  late FocusNode _nameFocus;

  late AnimationController _animator;
  late Animation<Color?> _color;
  late StreamSubscription<FingerprintEvent> _subscription;

  int _samples = 0;
  int _remaining = 5;
  Fingerprint? _fingerprint;
  String _label = '';

  @override
  void dispose() {
    _animator.dispose();
    _nameFocus.dispose();
    super.dispose();
  }

  Animation<Color?> _animateColor(Color color,
      {Function? atPeak, bool reverse = true}) {
    final animation =
        ColorTween(begin: Colors.black, end: color).animate(_animator);
    _animator.forward().then((_) {
      if (reverse) {
        atPeak?.call();
        _animator.reverse();
      }
    });
    return animation;
  }

  @override
  void initState() {
    super.initState();

    _nameFocus = FocusNode();
    _animator = AnimationController(
        vsync: this, duration: const Duration(milliseconds: 250));
    _color =
        ColorTween(begin: Colors.black, end: Colors.black).animate(_animator);

    _subscription = ref
        .read(fingerprintProvider(widget.node.path).notifier)
        .registerFingerprint()
        .listen((event) {
      setState(() {
        event.when(capture: (remaining) {
          _color = _animateColor(Colors.lightGreenAccent, atPeak: () {
            setState(() {
              _samples += 1;
              _remaining = remaining;
            });
          }, reverse: remaining > 0);
        }, complete: (fingerprint) {
          _remaining = 0;
          _fingerprint = fingerprint;
          // This needs a short delay to ensure the field is enabled first
          Timer(const Duration(milliseconds: 100), _nameFocus.requestFocus);
        }, error: (code) {
          _log.config('Fingerprint capture error (code: $code)');
          _color = _animateColor(Colors.redAccent);
        });
      });
    }, onError: (error, stacktrace) {
      _log.severe('Error adding fingerprint', error, stacktrace);
      Navigator.of(context).pop();
      showMessage(context, 'Error adding fingerprint');
    });
  }

  String _getMessage() {
    if (_samples == 0) {
      return 'Press your finger against the YubiKey to begin.';
    }
    if (_fingerprint == null) {
      return 'Keep touching your YubiKey repeatedly...';
    } else {
      return 'Fingerprint captured successfully!';
    }
  }

  @override
  Widget build(BuildContext context) {
    // If current device changes, we need to pop back to the main Page.
    ref.listen<DeviceNode?>(currentDeviceProvider, (previous, next) {
      // Prevent over-popping if reset causes currentDevice to change.
      Navigator.of(context).popUntil((route) => route.isFirst);
    });

    final progress = _samples == 0 ? 0.0 : _samples / (_samples + _remaining);

    return ResponsiveDialog(
      title: const Text('Add fingerprint'),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text('Step 1/2: Capture fingerprint'),
          Card(
            child: Column(
              children: [
                AnimatedBuilder(
                  animation: _color,
                  builder: (context, _) {
                    return Icon(
                      _fingerprint == null ? Icons.fingerprint : Icons.check,
                      size: 200.0,
                      color: _color.value,
                    );
                  },
                ),
                LinearProgressIndicator(value: progress),
                Padding(
                  padding: const EdgeInsets.all(8.0),
                  child: Text(_getMessage()),
                ),
              ],
            ),
          ),
          const Text('Step 2/2: Name fingerprint'),
          TextFormField(
            focusNode: _nameFocus,
            maxLength: 15,
            autofocus: true,
            decoration: InputDecoration(
              enabled: _fingerprint != null,
              border: const OutlineInputBorder(),
              labelText: 'Name',
            ),
            onChanged: (value) {
              setState(() {
                _label = value.trim();
              });
            },
          ),
        ]
            .map((e) => Padding(
                  child: e,
                  padding: const EdgeInsets.symmetric(vertical: 8.0),
                ))
            .toList(),
      ),
      onCancel: () {
        _subscription.cancel();
      },
      actions: [
        TextButton(
          onPressed: _fingerprint != null && _label.isNotEmpty
              ? () async {
                  await ref
                      .read(fingerprintProvider(widget.node.path).notifier)
                      .renameFingerprint(_fingerprint!, _label);
                  Navigator.of(context).pop(true);
                  showMessage(context, 'Fingerprint added');
                }
              : null,
          child: const Text('Save'),
        ),
      ],
    );
  }
}

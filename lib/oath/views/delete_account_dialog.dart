import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../app/message.dart';
import '../../app/views/responsive_dialog.dart';
import '../models.dart';
import '../state.dart';
import '../../app/models.dart';
import '../../app/state.dart';

class DeleteAccountDialog extends ConsumerWidget {
  final DeviceNode device;
  final OathCredential credential;
  const DeleteAccountDialog(this.device, this.credential, {Key? key})
      : super(key: key);

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    // If current device changes, we need to pop back to the main Page.
    ref.listen<DeviceNode?>(currentDeviceProvider, (previous, next) {
      Navigator.of(context).pop(false);
    });

    final label = credential.issuer != null
        ? '${credential.issuer} (${credential.name})'
        : credential.name;

    return ResponsiveDialog(
      title: const Text('Delete account'),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text(
              'Warning! This action will delete the account from your YubiKey.'),
          Text(
            'You will no longer be able to generate OTPs for this account. Make sure to first disable this credential from the website to avoid being locked out of your account.',
            style: Theme.of(context).textTheme.bodyText1,
          ),
          Text('Account: $label'),
        ]
            .map((e) => Padding(
                  child: e,
                  padding: const EdgeInsets.symmetric(vertical: 8.0),
                ))
            .toList(),
      ),
      actions: [
        TextButton(
          onPressed: () async {
            await ref
                .read(credentialListProvider(device.path).notifier)
                .deleteAccount(credential);
            Navigator.of(context).pop(true);
            showMessage(context, 'Account deleted');
          },
          child: const Text('Delete'),
        ),
      ],
    );
  }
}

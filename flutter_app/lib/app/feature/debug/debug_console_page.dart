import 'dart:async';

import 'package:flutter/material.dart';

import '../../core/network/ws_client.dart';
import '../../core/network/ws_traffic_message.dart';

/// 调试控制台页，展示最近 100 条收发消息。
class DebugConsolePage extends StatefulWidget {
  final WsClient wsClient;

  const DebugConsolePage({super.key, required this.wsClient});

  @override
  State<DebugConsolePage> createState() => _DebugConsolePageState();
}

class _DebugConsolePageState extends State<DebugConsolePage> {
  final List<WsTrafficMessage> _items = <WsTrafficMessage>[];
  StreamSubscription<WsTrafficMessage>? _subscription;

  @override
  void initState() {
    super.initState();
    _subscription = widget.wsClient.traffic.listen((WsTrafficMessage item) {
      setState(() {
        _items.insert(0, item);
        if (_items.length > 100) {
          _items.removeRange(100, _items.length);
        }
      });
    });
  }

  @override
  void dispose() {
    _subscription?.cancel();
    _subscription = null;
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    if (_items.isEmpty) {
      return const Center(child: Text('暂无收发消息'));
    }
    return ListView.builder(
      padding: const EdgeInsets.all(12),
      itemCount: _items.length,
      itemBuilder: (BuildContext context, int index) {
        final WsTrafficMessage item = _items[index];
        final String direction =
            item.direction == WsTrafficDirection.inbound ? '<=' : '=>';
        return ListTile(
          dense: true,
          title: Text('$direction ${item.type}'),
          subtitle: Text(item.payload.toString()),
        );
      },
    );
  }
}

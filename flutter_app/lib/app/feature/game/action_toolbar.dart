import 'package:flutter/material.dart';

/// 对局操作工具栏，仅提供最小动作按钮。
class ActionToolbar extends StatelessWidget {
  final VoidCallback onDraw;
  final VoidCallback onEndTurn;
  final VoidCallback onPlayDeposit;

  const ActionToolbar({
    super.key,
    required this.onDraw,
    required this.onEndTurn,
    required this.onPlayDeposit,
  });

  @override
  Widget build(BuildContext context) {
    return Wrap(
      spacing: 8,
      runSpacing: 8,
      children: <Widget>[
        OutlinedButton(onPressed: onDraw, child: const Text('DRAW')),
        OutlinedButton(onPressed: onEndTurn, child: const Text('END_TURN')),
        OutlinedButton(onPressed: onPlayDeposit, child: const Text('PLAY(DEPOSIT)')),
      ],
    );
  }
}

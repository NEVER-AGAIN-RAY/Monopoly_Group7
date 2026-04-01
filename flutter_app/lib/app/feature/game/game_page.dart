import 'package:flutter/material.dart';

import 'action_toolbar.dart';
import 'game_view_model.dart';

/// 对局页，展示基础状态与消息反馈。
class GamePage extends StatefulWidget {
  final GameViewModel viewModel;

  const GamePage({super.key, required this.viewModel});

  @override
  State<GamePage> createState() => _GamePageState();
}

class _GamePageState extends State<GamePage> {
  @override
  void initState() {
    super.initState();
    widget.viewModel.start();
  }

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: widget.viewModel,
      builder: (BuildContext context, Widget? child) {
        final state = widget.viewModel.state;
        return ListView(
          padding: const EdgeInsets.all(12),
          children: <Widget>[
            ActionToolbar(
              onDraw: widget.viewModel.draw,
              onEndTurn: widget.viewModel.endTurn,
              onPlayDeposit: widget.viewModel.playDepositExample,
            ),
            const SizedBox(height: 12),
            const Text('STATE_UPDATE', style: TextStyle(fontWeight: FontWeight.bold)),
            const SizedBox(height: 6),
            SelectableText(_pretty(state.snapshot)),
            const SizedBox(height: 16),
            const Text('MY_HAND', style: TextStyle(fontWeight: FontWeight.bold)),
            const SizedBox(height: 6),
            SelectableText(_pretty(state.myHand)),
            const SizedBox(height: 16),
            const Text('RESULT FEEDBACK', style: TextStyle(fontWeight: FontWeight.bold)),
            const SizedBox(height: 6),
            if (state.feedback.isEmpty)
              const Text('暂无反馈')
            else
              ...state.feedback.map((String line) => Text(line)),
          ],
        );
      },
    );
  }

  @override
  void dispose() {
    widget.viewModel.stop();
    super.dispose();
  }

  String _pretty(Map<String, dynamic>? data) {
    if (data == null) {
      return '-';
    }
    return data.toString();
  }
}

import 'dart:async';

import 'package:flutter/material.dart';

import '../models/chat.dart';
import '../models/message.dart';
import '../theme/app_theme.dart';
import '../widgets/avatar.dart';
import '../widgets/message_bubble.dart';

/// شاشة المحادثة: عرض الرسايل وإرسال رسالة جديدة.
class ChatScreen extends StatefulWidget {
  const ChatScreen({super.key, required this.chat});

  final Chat chat;

  @override
  State<ChatScreen> createState() => _ChatScreenState();
}

class _ChatScreenState extends State<ChatScreen> {
  final TextEditingController _input = TextEditingController();
  final ScrollController _scrollController = ScrollController();
  Timer? _replyTimer;
  int _replyCount = 0;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      _scrollToBottom(animate: false);
    });
  }

  @override
  void dispose() {
    _replyTimer?.cancel();
    _input.dispose();
    _scrollController.dispose();
    super.dispose();
  }

  void _scrollToBottom({bool animate = true}) {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!_scrollController.hasClients) {
        return;
      }
      final double target = _scrollController.position.maxScrollExtent;
      if (animate) {
        _scrollController.animateTo(
          target,
          duration: const Duration(milliseconds: 250),
          curve: Curves.easeOut,
        );
      } else {
        _scrollController.jumpTo(target);
      }
    });
  }

  void _send() {
    final String text = _input.text.trim();
    if (text.isEmpty) {
      return;
    }

    setState(() {
      widget.chat.messages.add(
        Message(
          id: 'local-${DateTime.now().microsecondsSinceEpoch}',
          text: text,
          time: DateTime.now(),
          isMe: true,
          status: MessageStatus.sent,
        ),
      );
    });
    _input.clear();
    _scrollToBottom();
    _scheduleReply();
  }

  /// رد تلقائي بعد أقل من ثانية — لإحساس المحادثة الحقيقية (تجريبي).
  void _scheduleReply() {
    _replyTimer?.cancel();
    _replyTimer = Timer(const Duration(milliseconds: 900), () {
      if (!mounted) {
        return;
      }
      _replyCount += 1;
      setState(() {
        widget.chat.messages.add(
          Message(
            id: 'auto-${DateTime.now().microsecondsSinceEpoch}',
            text: _replyCount == 1
                ? 'وصلت رسالتك ✅ (رد تلقائي تجريبي)'
                : 'تمام 👌 (رد تلقائي تجريبي)',
            time: DateTime.now(),
            isMe: false,
          ),
        );
      });
      _scrollToBottom();
    });
  }

  void _showDemoMessage(String text) {
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(text)),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        titleSpacing: 0,
        title: Row(
          children: <Widget>[
            Avatar(name: widget.chat.name, size: 38),
            const SizedBox(width: 10),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                mainAxisSize: MainAxisSize.min,
                children: <Widget>[
                  Text(
                    widget.chat.name,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(
                      fontSize: 16,
                      fontWeight: FontWeight.w600,
                      color: Colors.white,
                    ),
                  ),
                  Text(
                    widget.chat.isOnline ? 'متصل الآن' : 'غير متصل',
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(
                      fontSize: 12,
                      color: Colors.white70,
                    ),
                  ),
                ],
              ),
            ),
          ],
        ),
        actions: <Widget>[
          IconButton(
            icon: const Icon(Icons.videocam_outlined),
            tooltip: 'مكالمة فيديو',
            onPressed: () => _showDemoMessage('مكالمة فيديو — تجريبي'),
          ),
          IconButton(
            icon: const Icon(Icons.call_outlined),
            tooltip: 'مكالمة صوتية',
            onPressed: () => _showDemoMessage('مكالمة صوتية — تجريبي'),
          ),
          PopupMenuButton<String>(
            tooltip: 'المزيد',
            icon: const Icon(Icons.more_vert),
            onSelected: (String value) {
              if (value == 'clear') {
                setState(widget.chat.messages.clear);
              } else if (value == 'info') {
                _showDemoMessage('معلومات المحادثة — تجريبي');
              } else if (value == 'block') {
                _showDemoMessage('تم الحظر (تجريبي)');
              }
            },
            itemBuilder: (BuildContext context) =>
                const <PopupMenuEntry<String>>[
              PopupMenuItem<String>(
                value: 'info',
                child: Text('معلومات المحادثة'),
              ),
              PopupMenuItem<String>(
                value: 'clear',
                child: Text('مسح المحادثة'),
              ),
              PopupMenuItem<String>(
                value: 'block',
                child: Text('حظر'),
              ),
            ],
          ),
        ],
      ),
      body: Column(
        children: <Widget>[
          Expanded(
            child: Container(
              color: AppColors.chatBackground(context),
              child: ListView.builder(
                controller: _scrollController,
                padding: const EdgeInsets.symmetric(vertical: 10, horizontal: 4),
                itemCount: widget.chat.messages.length + 1,
                itemBuilder: (BuildContext context, int index) {
                  if (index == 0) {
                    return const _DateChip();
                  }
                  return MessageBubble(
                    message: widget.chat.messages[index - 1],
                  );
                },
              ),
            ),
          ),
          _buildComposer(context),
        ],
      ),
    );
  }

  Widget _buildComposer(BuildContext context) {
    final Color secondary = AppColors.secondaryText(context);

    return SafeArea(
      top: false,
      child: Container(
        padding: const EdgeInsets.fromLTRB(8, 8, 8, 8),
        color: AppColors.composer(context),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.end,
          children: <Widget>[
            Expanded(
              child: Container(
                padding: const EdgeInsets.symmetric(horizontal: 10),
                decoration: BoxDecoration(
                  color: AppColors.surfaceAlt(context),
                  borderRadius: BorderRadius.circular(24),
                ),
                child: Row(
                  crossAxisAlignment: CrossAxisAlignment.end,
                  children: <Widget>[
                    Icon(Icons.emoji_emotions_outlined,
                        size: 22, color: secondary),
                    const SizedBox(width: 6),
                    Expanded(
                      child: TextField(
                        controller: _input,
                        minLines: 1,
                        maxLines: 4,
                        textInputAction: TextInputAction.send,
                        onSubmitted: (String _) => _send(),
                        decoration: const InputDecoration(
                          hintText: 'اكتب رسالة',
                          border: InputBorder.none,
                          isDense: true,
                        ),
                      ),
                    ),
                    IconButton(
                      icon: Icon(Icons.attach_file, size: 20, color: secondary),
                      tooltip: 'إرفاق',
                      onPressed: () => _showDemoMessage('إرفاق ملف — تجريبي'),
                    ),
                  ],
                ),
              ),
            ),
            const SizedBox(width: 8),
            Material(
              color: AppColors.teal,
              shape: const CircleBorder(),
              child: IconButton(
                icon: const Icon(Icons.send, color: Colors.white, size: 20),
                tooltip: 'إرسال',
                onPressed: _send,
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _DateChip extends StatelessWidget {
  const _DateChip();

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Container(
        margin: const EdgeInsets.only(bottom: 10, top: 2),
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 5),
        decoration: BoxDecoration(
          color: AppColors.surfaceAlt(context),
          borderRadius: BorderRadius.circular(8),
          boxShadow: const <BoxShadow>[
            BoxShadow(
              color: Color(0x14000000),
              blurRadius: 1,
              offset: Offset(0, 1),
            ),
          ],
        ),
        child: Text(
          'اليوم',
          style: TextStyle(
            fontSize: 11.5,
            color: AppColors.secondaryText(context),
            fontWeight: FontWeight.w500,
          ),
        ),
      ),
    );
  }
}

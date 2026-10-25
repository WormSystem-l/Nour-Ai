import 'package:flutter/material.dart';

import '../models/chat.dart';
import '../theme/app_theme.dart';
import '../widgets/chat_tile.dart';

/// تبويب المحادثات مع بحث محلي.
class ChatsTab extends StatefulWidget {
  const ChatsTab({super.key, required this.chats, required this.onOpenChat});

  final List<Chat> chats;
  final ValueChanged<Chat> onOpenChat;

  @override
  State<ChatsTab> createState() => _ChatsTabState();
}

class _ChatsTabState extends State<ChatsTab> {
  final TextEditingController _searchController = TextEditingController();
  String _query = '';

  @override
  void dispose() {
    _searchController.dispose();
    super.dispose();
  }

  List<Chat> get _visibleChats {
    final String query = _query.trim();
    if (query.isEmpty) {
      return widget.chats;
    }
    return widget.chats
        .where((Chat chat) =>
            chat.name.contains(query) || chat.preview.contains(query))
        .toList();
  }

  @override
  Widget build(BuildContext context) {
    final Color secondary = AppColors.secondaryText(context);
    final List<Chat> visible = _visibleChats;

    return Column(
      children: <Widget>[
        Padding(
          padding: const EdgeInsets.fromLTRB(12, 10, 12, 6),
          child: Container(
            padding: const EdgeInsets.symmetric(horizontal: 12),
            decoration: BoxDecoration(
              color: AppColors.surfaceAlt(context),
              borderRadius: BorderRadius.circular(24),
            ),
            child: Row(
              children: <Widget>[
                Icon(Icons.search, size: 20, color: secondary),
                const SizedBox(width: 8),
                Expanded(
                  child: TextField(
                    controller: _searchController,
                    onChanged: (String value) {
                      setState(() => _query = value);
                    },
                    decoration: const InputDecoration(
                      hintText: 'ابحث في المحادثات',
                      border: InputBorder.none,
                      isDense: true,
                    ),
                  ),
                ),
                if (_query.isNotEmpty)
                  GestureDetector(
                    onTap: () {
                      _searchController.clear();
                      setState(() => _query = '');
                    },
                    child: Icon(Icons.close, size: 18, color: secondary),
                  ),
              ],
            ),
          ),
        ),
        Expanded(
          child: visible.isEmpty
              ? const _EmptyState()
              : ListView.separated(
                  itemCount: visible.length,
                  separatorBuilder: (BuildContext context, int index) =>
                      Divider(
                    height: 1,
                    indent: 82,
                    color: secondary.withAlpha(38),
                  ),
                  itemBuilder: (BuildContext context, int index) {
                    final Chat chat = visible[index];
                    return ChatTile(
                      chat: chat,
                      onTap: () => widget.onOpenChat(chat),
                    );
                  },
                ),
        ),
      ],
    );
  }
}

class _EmptyState extends StatelessWidget {
  const _EmptyState();

  @override
  Widget build(BuildContext context) {
    final Color secondary = AppColors.secondaryText(context);
    return Center(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: <Widget>[
          Icon(Icons.search_off, size: 56, color: secondary),
          const SizedBox(height: 12),
          Text(
            'مفيش محادثات مطابقة',
            style: TextStyle(fontSize: 15, color: secondary),
          ),
        ],
      ),
    );
  }
}

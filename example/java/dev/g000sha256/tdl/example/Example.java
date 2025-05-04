//
// Copyright Aliaksei Levin (levlam@telegram.org), Arseny Smirnov (arseny30@gmail.com) 2014-2025
//
// Distributed under the Boost Software License, Version 1.0. (See accompanying
// file LICENSE_1_0.txt or copy at http://www.boost.org/LICENSE_1_0.txt)
//
package dev.g000sha256.tdl.example;

import dev.g000sha256.tdl.Client;
import dev.g000sha256.tdl.TdlApi;

import java.io.BufferedReader;
import java.io.IOError;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.NavigableSet;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Example class for TDLib usage from Java.
 */
public final class Example {
    private static Client client = null;

    private static TdlApi.AuthorizationState authorizationState = null;
    private static volatile boolean haveAuthorization = false;
    private static volatile boolean needQuit = false;
    private static volatile boolean canQuit = false;

    private static final Client.ResultHandler defaultHandler = new DefaultHandler();

    private static final Lock authorizationLock = new ReentrantLock();
    private static final Condition gotAuthorization = authorizationLock.newCondition();

    private static final ConcurrentMap<Long, TdlApi.User> users = new ConcurrentHashMap<Long, TdlApi.User>();
    private static final ConcurrentMap<Long, TdlApi.BasicGroup> basicGroups = new ConcurrentHashMap<Long, TdlApi.BasicGroup>();
    private static final ConcurrentMap<Long, TdlApi.Supergroup> supergroups = new ConcurrentHashMap<Long, TdlApi.Supergroup>();
    private static final ConcurrentMap<Integer, TdlApi.SecretChat> secretChats = new ConcurrentHashMap<Integer, TdlApi.SecretChat>();

    private static final ConcurrentMap<Long, TdlApi.Chat> chats = new ConcurrentHashMap<Long, TdlApi.Chat>();
    private static final NavigableSet<OrderedChat> mainChatList = new TreeSet<OrderedChat>();
    private static boolean haveFullMainChatList = false;

    private static final ConcurrentMap<Long, TdlApi.UserFullInfo> usersFullInfo = new ConcurrentHashMap<Long, TdlApi.UserFullInfo>();
    private static final ConcurrentMap<Long, TdlApi.BasicGroupFullInfo> basicGroupsFullInfo = new ConcurrentHashMap<Long, TdlApi.BasicGroupFullInfo>();
    private static final ConcurrentMap<Long, TdlApi.SupergroupFullInfo> supergroupsFullInfo = new ConcurrentHashMap<Long, TdlApi.SupergroupFullInfo>();

    private static final String newLine = System.getProperty("line.separator");
    private static final String commandsLine = "Enter command (gcs - GetChats, gc <chatId> - GetChat, me - GetMe, sm <chatId> <message> - SendMessage, lo - LogOut, q - Quit): ";
    private static volatile String currentPrompt = null;

    private static void print(String str) {
        if (currentPrompt != null) {
            System.out.println("");
        }
        System.out.println(str);
        if (currentPrompt != null) {
            System.out.print(currentPrompt);
        }
    }

    private static void setChatPositions(TdlApi.Chat chat, TdlApi.ChatPosition[] positions) {
        synchronized (mainChatList) {
            synchronized (chat) {
                for (TdlApi.ChatPosition position : chat.positions) {
                    if (position.list.getConstructor() == TdlApi.ChatListMain.CONSTRUCTOR) {
                        boolean isRemoved = mainChatList.remove(new OrderedChat(chat.id, position));
                        assert isRemoved;
                    }
                }

                chat.positions = positions;

                for (TdlApi.ChatPosition position : chat.positions) {
                    if (position.list.getConstructor() == TdlApi.ChatListMain.CONSTRUCTOR) {
                        boolean isAdded = mainChatList.add(new OrderedChat(chat.id, position));
                        assert isAdded;
                    }
                }
            }
        }
    }

    private static void onAuthorizationStateUpdated(TdlApi.AuthorizationState authorizationState) {
        if (authorizationState != null) {
            Example.authorizationState = authorizationState;
        }
        switch (Example.authorizationState.getConstructor()) {
            case TdlApi.AuthorizationStateWaitTdlibParameters.CONSTRUCTOR:
                TdlApi.SetTdlibParameters request = new TdlApi.SetTdlibParameters();
                request.databaseDirectory = "tdlib";
                request.useMessageDatabase = true;
                request.useSecretChats = true;
                request.apiId = 94575;
                request.apiHash = "a3406de8d171bb422bb6ddf3bbd800e2";
                request.systemLanguageCode = "en";
                request.deviceModel = "Desktop";
                request.applicationVersion = "1.0";

                client.send(request, new AuthorizationRequestHandler());
                break;
            case TdlApi.AuthorizationStateWaitPhoneNumber.CONSTRUCTOR: {
                String phoneNumber = promptString("Please enter phone number: ");
                client.send(new TdlApi.SetAuthenticationPhoneNumber(phoneNumber, null), new AuthorizationRequestHandler());
                break;
            }
            case TdlApi.AuthorizationStateWaitOtherDeviceConfirmation.CONSTRUCTOR: {
                String link = ((TdlApi.AuthorizationStateWaitOtherDeviceConfirmation) Example.authorizationState).link;
                System.out.println("Please confirm this login link on another device: " + link);
                break;
            }
            case TdlApi.AuthorizationStateWaitEmailAddress.CONSTRUCTOR: {
                String emailAddress = promptString("Please enter email address: ");
                client.send(new TdlApi.SetAuthenticationEmailAddress(emailAddress), new AuthorizationRequestHandler());
                break;
            }
            case TdlApi.AuthorizationStateWaitEmailCode.CONSTRUCTOR: {
                String code = promptString("Please enter email authentication code: ");
                client.send(new TdlApi.CheckAuthenticationEmailCode(new TdlApi.EmailAddressAuthenticationCode(code)), new AuthorizationRequestHandler());
                break;
            }
            case TdlApi.AuthorizationStateWaitCode.CONSTRUCTOR: {
                String code = promptString("Please enter authentication code: ");
                client.send(new TdlApi.CheckAuthenticationCode(code), new AuthorizationRequestHandler());
                break;
            }
            case TdlApi.AuthorizationStateWaitRegistration.CONSTRUCTOR: {
                String firstName = promptString("Please enter your first name: ");
                String lastName = promptString("Please enter your last name: ");
                client.send(new TdlApi.RegisterUser(firstName, lastName, false), new AuthorizationRequestHandler());
                break;
            }
            case TdlApi.AuthorizationStateWaitPassword.CONSTRUCTOR: {
                String password = promptString("Please enter password: ");
                client.send(new TdlApi.CheckAuthenticationPassword(password), new AuthorizationRequestHandler());
                break;
            }
            case TdlApi.AuthorizationStateReady.CONSTRUCTOR:
                haveAuthorization = true;
                authorizationLock.lock();
                try {
                    gotAuthorization.signal();
                } finally {
                    authorizationLock.unlock();
                }
                break;
            case TdlApi.AuthorizationStateLoggingOut.CONSTRUCTOR:
                haveAuthorization = false;
                print("Logging out");
                break;
            case TdlApi.AuthorizationStateClosing.CONSTRUCTOR:
                haveAuthorization = false;
                print("Closing");
                break;
            case TdlApi.AuthorizationStateClosed.CONSTRUCTOR:
                print("Closed");
                if (!needQuit) {
                    client = Client.create(new UpdateHandler(), null, null); // recreate client after previous has closed
                } else {
                    canQuit = true;
                }
                break;
            default:
                System.err.println("Unsupported authorization state:" + newLine + Example.authorizationState);
        }
    }

    private static int toInt(String arg) {
        int result = 0;
        try {
            result = Integer.parseInt(arg);
        } catch (NumberFormatException ignored) {
        }
        return result;
    }

    private static long getChatId(String arg) {
        long chatId = 0;
        try {
            chatId = Long.parseLong(arg);
        } catch (NumberFormatException ignored) {
        }
        return chatId;
    }

    private static String promptString(String prompt) {
        System.out.print(prompt);
        currentPrompt = prompt;
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        String str = "";
        try {
            str = reader.readLine();
        } catch (IOException e) {
            e.printStackTrace();
        }
        currentPrompt = null;
        return str;
    }

    private static void getCommand() {
        String command = promptString(commandsLine);
        String[] commands = command.split(" ", 2);
        try {
            switch (commands[0]) {
                case "gcs": {
                    int limit = 20;
                    if (commands.length > 1) {
                        limit = toInt(commands[1]);
                    }
                    getMainChatList(limit);
                    break;
                }
                case "gc":
                    client.send(new TdlApi.GetChat(getChatId(commands[1])), defaultHandler);
                    break;
                case "me":
                    client.send(new TdlApi.GetMe(), defaultHandler);
                    break;
                case "sm": {
                    String[] args = commands[1].split(" ", 2);
                    sendMessage(getChatId(args[0]), args[1]);
                    break;
                }
                case "lo":
                    haveAuthorization = false;
                    client.send(new TdlApi.LogOut(), defaultHandler);
                    break;
                case "q":
                    needQuit = true;
                    haveAuthorization = false;
                    client.send(new TdlApi.Close(), defaultHandler);
                    break;
                default:
                    System.err.println("Unsupported command: " + command);
            }
        } catch (ArrayIndexOutOfBoundsException e) {
            print("Not enough arguments");
        }
    }

    private static void getMainChatList(final int limit) {
        synchronized (mainChatList) {
            if (!haveFullMainChatList && limit > mainChatList.size()) {
                // send LoadChats request if there are some unknown chats and have not enough known chats
                client.send(new TdlApi.LoadChats(new TdlApi.ChatListMain(), limit - mainChatList.size()), new Client.ResultHandler() {
                    @Override
                    public void onResult(TdlApi.Object object) {
                        switch (object.getConstructor()) {
                            case TdlApi.Error.CONSTRUCTOR:
                                if (((TdlApi.Error) object).code == 404) {
                                    synchronized (mainChatList) {
                                        haveFullMainChatList = true;
                                    }
                                } else {
                                    System.err.println("Receive an error for LoadChats:" + newLine + object);
                                }
                                break;
                            case TdlApi.Ok.CONSTRUCTOR:
                                // chats had already been received through updates, let's retry request
                                getMainChatList(limit);
                                break;
                            default:
                                System.err.println("Receive wrong response from TDLib:" + newLine + object);
                        }
                    }
                });
                return;
            }

            java.util.Iterator<OrderedChat> iter = mainChatList.iterator();
            System.out.println();
            System.out.println("First " + limit + " chat(s) out of " + mainChatList.size() + " known chat(s):");
            for (int i = 0; i < limit && i < mainChatList.size(); i++) {
                long chatId = iter.next().chatId;
                TdlApi.Chat chat = chats.get(chatId);
                synchronized (chat) {
                    System.out.println(chatId + ": " + chat.title);
                }
            }
            print("");
        }
    }

    private static void sendMessage(long chatId, String message) {
        // initialize reply markup just for testing
        TdlApi.InlineKeyboardButton[] row = {new TdlApi.InlineKeyboardButton("https://telegram.org?1", new TdlApi.InlineKeyboardButtonTypeUrl()), new TdlApi.InlineKeyboardButton("https://telegram.org?2", new TdlApi.InlineKeyboardButtonTypeUrl()), new TdlApi.InlineKeyboardButton("https://telegram.org?3", new TdlApi.InlineKeyboardButtonTypeUrl())};
        TdlApi.ReplyMarkup replyMarkup = new TdlApi.ReplyMarkupInlineKeyboard(new TdlApi.InlineKeyboardButton[][]{row, row, row});

        TdlApi.InputMessageContent content = new TdlApi.InputMessageText(new TdlApi.FormattedText(message, null), null, true);
        client.send(new TdlApi.SendMessage(chatId, 0, null, null, replyMarkup, content), defaultHandler);
    }

    public static void main(String[] args) throws InterruptedException {
        // set log message handler to handle only fatal errors (0) and plain log messages (-1)
        Client.setLogMessageHandler(0, new LogMessageHandler());

        // disable TDLib log and redirect fatal errors and plain log messages to a file
        try {
            Client.execute(new TdlApi.SetLogVerbosityLevel(0));
            Client.execute(new TdlApi.SetLogStream(new TdlApi.LogStreamFile("tdlib.log", 1 << 27, false)));
        } catch (Client.ExecutionException error) {
            throw new IOError(new IOException("Write access to the current directory is required"));
        }

        // create client
        client = Client.create(new UpdateHandler(), null, null);

        // main loop
        while (!needQuit) {
            // await authorization
            authorizationLock.lock();
            try {
                while (!haveAuthorization) {
                    gotAuthorization.await();
                }
            } finally {
                authorizationLock.unlock();
            }

            while (haveAuthorization) {
                getCommand();
            }
        }
        while (!canQuit) {
            Thread.sleep(1);
        }
    }

    private static class OrderedChat implements Comparable<OrderedChat> {
        final long chatId;
        final TdlApi.ChatPosition position;

        OrderedChat(long chatId, TdlApi.ChatPosition position) {
            this.chatId = chatId;
            this.position = position;
        }

        @Override
        public int compareTo(OrderedChat o) {
            if (this.position.order != o.position.order) {
                return o.position.order < this.position.order ? -1 : 1;
            }
            if (this.chatId != o.chatId) {
                return o.chatId < this.chatId ? -1 : 1;
            }
            return 0;
        }

        @Override
        public boolean equals(Object obj) {
            OrderedChat o = (OrderedChat) obj;
            return this.chatId == o.chatId && this.position.order == o.position.order;
        }
    }

    private static class DefaultHandler implements Client.ResultHandler {
        @Override
        public void onResult(TdlApi.Object object) {
            print(object.toString());
        }
    }

    private static class UpdateHandler implements Client.ResultHandler {
        @Override
        public void onResult(TdlApi.Object object) {
            switch (object.getConstructor()) {
                case TdlApi.UpdateAuthorizationState.CONSTRUCTOR:
                    onAuthorizationStateUpdated(((TdlApi.UpdateAuthorizationState) object).authorizationState);
                    break;

                case TdlApi.UpdateUser.CONSTRUCTOR:
                    TdlApi.UpdateUser updateUser = (TdlApi.UpdateUser) object;
                    users.put(updateUser.user.id, updateUser.user);
                    break;
                case TdlApi.UpdateUserStatus.CONSTRUCTOR: {
                    TdlApi.UpdateUserStatus updateUserStatus = (TdlApi.UpdateUserStatus) object;
                    TdlApi.User user = users.get(updateUserStatus.userId);
                    synchronized (user) {
                        user.status = updateUserStatus.status;
                    }
                    break;
                }
                case TdlApi.UpdateBasicGroup.CONSTRUCTOR:
                    TdlApi.UpdateBasicGroup updateBasicGroup = (TdlApi.UpdateBasicGroup) object;
                    basicGroups.put(updateBasicGroup.basicGroup.id, updateBasicGroup.basicGroup);
                    break;
                case TdlApi.UpdateSupergroup.CONSTRUCTOR:
                    TdlApi.UpdateSupergroup updateSupergroup = (TdlApi.UpdateSupergroup) object;
                    supergroups.put(updateSupergroup.supergroup.id, updateSupergroup.supergroup);
                    break;
                case TdlApi.UpdateSecretChat.CONSTRUCTOR:
                    TdlApi.UpdateSecretChat updateSecretChat = (TdlApi.UpdateSecretChat) object;
                    secretChats.put(updateSecretChat.secretChat.id, updateSecretChat.secretChat);
                    break;

                case TdlApi.UpdateNewChat.CONSTRUCTOR: {
                    TdlApi.UpdateNewChat updateNewChat = (TdlApi.UpdateNewChat) object;
                    TdlApi.Chat chat = updateNewChat.chat;
                    synchronized (chat) {
                        chats.put(chat.id, chat);

                        TdlApi.ChatPosition[] positions = chat.positions;
                        chat.positions = new TdlApi.ChatPosition[0];
                        setChatPositions(chat, positions);
                    }
                    break;
                }
                case TdlApi.UpdateChatTitle.CONSTRUCTOR: {
                    TdlApi.UpdateChatTitle updateChat = (TdlApi.UpdateChatTitle) object;
                    TdlApi.Chat chat = chats.get(updateChat.chatId);
                    synchronized (chat) {
                        chat.title = updateChat.title;
                    }
                    break;
                }
                case TdlApi.UpdateChatPhoto.CONSTRUCTOR: {
                    TdlApi.UpdateChatPhoto updateChat = (TdlApi.UpdateChatPhoto) object;
                    TdlApi.Chat chat = chats.get(updateChat.chatId);
                    synchronized (chat) {
                        chat.photo = updateChat.photo;
                    }
                    break;
                }
                case TdlApi.UpdateChatPermissions.CONSTRUCTOR: {
                    TdlApi.UpdateChatPermissions update = (TdlApi.UpdateChatPermissions) object;
                    TdlApi.Chat chat = chats.get(update.chatId);
                    synchronized (chat) {
                        chat.permissions = update.permissions;
                    }
                    break;
                }
                case TdlApi.UpdateChatLastMessage.CONSTRUCTOR: {
                    TdlApi.UpdateChatLastMessage updateChat = (TdlApi.UpdateChatLastMessage) object;
                    TdlApi.Chat chat = chats.get(updateChat.chatId);
                    synchronized (chat) {
                        chat.lastMessage = updateChat.lastMessage;
                        setChatPositions(chat, updateChat.positions);
                    }
                    break;
                }
                case TdlApi.UpdateChatPosition.CONSTRUCTOR: {
                    TdlApi.UpdateChatPosition updateChat = (TdlApi.UpdateChatPosition) object;
                    if (updateChat.position.list.getConstructor() != TdlApi.ChatListMain.CONSTRUCTOR) {
                        break;
                    }

                    TdlApi.Chat chat = chats.get(updateChat.chatId);
                    synchronized (chat) {
                        int i;
                        for (i = 0; i < chat.positions.length; i++) {
                            if (chat.positions[i].list.getConstructor() == TdlApi.ChatListMain.CONSTRUCTOR) {
                                break;
                            }
                        }
                        TdlApi.ChatPosition[] new_positions = new TdlApi.ChatPosition[chat.positions.length + (updateChat.position.order == 0 ? 0 : 1) - (i < chat.positions.length ? 1 : 0)];
                        int pos = 0;
                        if (updateChat.position.order != 0) {
                            new_positions[pos++] = updateChat.position;
                        }
                        for (int j = 0; j < chat.positions.length; j++) {
                            if (j != i) {
                                new_positions[pos++] = chat.positions[j];
                            }
                        }
                        assert pos == new_positions.length;

                        setChatPositions(chat, new_positions);
                    }
                    break;
                }
                case TdlApi.UpdateChatReadInbox.CONSTRUCTOR: {
                    TdlApi.UpdateChatReadInbox updateChat = (TdlApi.UpdateChatReadInbox) object;
                    TdlApi.Chat chat = chats.get(updateChat.chatId);
                    synchronized (chat) {
                        chat.lastReadInboxMessageId = updateChat.lastReadInboxMessageId;
                        chat.unreadCount = updateChat.unreadCount;
                    }
                    break;
                }
                case TdlApi.UpdateChatReadOutbox.CONSTRUCTOR: {
                    TdlApi.UpdateChatReadOutbox updateChat = (TdlApi.UpdateChatReadOutbox) object;
                    TdlApi.Chat chat = chats.get(updateChat.chatId);
                    synchronized (chat) {
                        chat.lastReadOutboxMessageId = updateChat.lastReadOutboxMessageId;
                    }
                    break;
                }
                case TdlApi.UpdateChatActionBar.CONSTRUCTOR: {
                    TdlApi.UpdateChatActionBar updateChat = (TdlApi.UpdateChatActionBar) object;
                    TdlApi.Chat chat = chats.get(updateChat.chatId);
                    synchronized (chat) {
                        chat.actionBar = updateChat.actionBar;
                    }
                    break;
                }
                case TdlApi.UpdateChatAvailableReactions.CONSTRUCTOR: {
                    TdlApi.UpdateChatAvailableReactions updateChat = (TdlApi.UpdateChatAvailableReactions) object;
                    TdlApi.Chat chat = chats.get(updateChat.chatId);
                    synchronized (chat) {
                        chat.availableReactions = updateChat.availableReactions;
                    }
                    break;
                }
                case TdlApi.UpdateChatDraftMessage.CONSTRUCTOR: {
                    TdlApi.UpdateChatDraftMessage updateChat = (TdlApi.UpdateChatDraftMessage) object;
                    TdlApi.Chat chat = chats.get(updateChat.chatId);
                    synchronized (chat) {
                        chat.draftMessage = updateChat.draftMessage;
                        setChatPositions(chat, updateChat.positions);
                    }
                    break;
                }
                case TdlApi.UpdateChatMessageSender.CONSTRUCTOR: {
                    TdlApi.UpdateChatMessageSender updateChat = (TdlApi.UpdateChatMessageSender) object;
                    TdlApi.Chat chat = chats.get(updateChat.chatId);
                    synchronized (chat) {
                        chat.messageSenderId = updateChat.messageSenderId;
                    }
                    break;
                }
                case TdlApi.UpdateChatMessageAutoDeleteTime.CONSTRUCTOR: {
                    TdlApi.UpdateChatMessageAutoDeleteTime updateChat = (TdlApi.UpdateChatMessageAutoDeleteTime) object;
                    TdlApi.Chat chat = chats.get(updateChat.chatId);
                    synchronized (chat) {
                        chat.messageAutoDeleteTime = updateChat.messageAutoDeleteTime;
                    }
                    break;
                }
                case TdlApi.UpdateChatNotificationSettings.CONSTRUCTOR: {
                    TdlApi.UpdateChatNotificationSettings update = (TdlApi.UpdateChatNotificationSettings) object;
                    TdlApi.Chat chat = chats.get(update.chatId);
                    synchronized (chat) {
                        chat.notificationSettings = update.notificationSettings;
                    }
                    break;
                }
                case TdlApi.UpdateChatPendingJoinRequests.CONSTRUCTOR: {
                    TdlApi.UpdateChatPendingJoinRequests update = (TdlApi.UpdateChatPendingJoinRequests) object;
                    TdlApi.Chat chat = chats.get(update.chatId);
                    synchronized (chat) {
                        chat.pendingJoinRequests = update.pendingJoinRequests;
                    }
                    break;
                }
                case TdlApi.UpdateChatReplyMarkup.CONSTRUCTOR: {
                    TdlApi.UpdateChatReplyMarkup updateChat = (TdlApi.UpdateChatReplyMarkup) object;
                    TdlApi.Chat chat = chats.get(updateChat.chatId);
                    synchronized (chat) {
                        chat.replyMarkupMessageId = updateChat.replyMarkupMessageId;
                    }
                    break;
                }
                case TdlApi.UpdateChatBackground.CONSTRUCTOR: {
                    TdlApi.UpdateChatBackground updateChat = (TdlApi.UpdateChatBackground) object;
                    TdlApi.Chat chat = chats.get(updateChat.chatId);
                    synchronized (chat) {
                        chat.background = updateChat.background;
                    }
                    break;
                }
                case TdlApi.UpdateChatTheme.CONSTRUCTOR: {
                    TdlApi.UpdateChatTheme updateChat = (TdlApi.UpdateChatTheme) object;
                    TdlApi.Chat chat = chats.get(updateChat.chatId);
                    synchronized (chat) {
                        chat.themeName = updateChat.themeName;
                    }
                    break;
                }
                case TdlApi.UpdateChatUnreadMentionCount.CONSTRUCTOR: {
                    TdlApi.UpdateChatUnreadMentionCount updateChat = (TdlApi.UpdateChatUnreadMentionCount) object;
                    TdlApi.Chat chat = chats.get(updateChat.chatId);
                    synchronized (chat) {
                        chat.unreadMentionCount = updateChat.unreadMentionCount;
                    }
                    break;
                }
                case TdlApi.UpdateChatUnreadReactionCount.CONSTRUCTOR: {
                    TdlApi.UpdateChatUnreadReactionCount updateChat = (TdlApi.UpdateChatUnreadReactionCount) object;
                    TdlApi.Chat chat = chats.get(updateChat.chatId);
                    synchronized (chat) {
                        chat.unreadReactionCount = updateChat.unreadReactionCount;
                    }
                    break;
                }
                case TdlApi.UpdateChatVideoChat.CONSTRUCTOR: {
                    TdlApi.UpdateChatVideoChat updateChat = (TdlApi.UpdateChatVideoChat) object;
                    TdlApi.Chat chat = chats.get(updateChat.chatId);
                    synchronized (chat) {
                        chat.videoChat = updateChat.videoChat;
                    }
                    break;
                }
                case TdlApi.UpdateChatDefaultDisableNotification.CONSTRUCTOR: {
                    TdlApi.UpdateChatDefaultDisableNotification update = (TdlApi.UpdateChatDefaultDisableNotification) object;
                    TdlApi.Chat chat = chats.get(update.chatId);
                    synchronized (chat) {
                        chat.defaultDisableNotification = update.defaultDisableNotification;
                    }
                    break;
                }
                case TdlApi.UpdateChatHasProtectedContent.CONSTRUCTOR: {
                    TdlApi.UpdateChatHasProtectedContent updateChat = (TdlApi.UpdateChatHasProtectedContent) object;
                    TdlApi.Chat chat = chats.get(updateChat.chatId);
                    synchronized (chat) {
                        chat.hasProtectedContent = updateChat.hasProtectedContent;
                    }
                    break;
                }
                case TdlApi.UpdateChatIsTranslatable.CONSTRUCTOR: {
                    TdlApi.UpdateChatIsTranslatable update = (TdlApi.UpdateChatIsTranslatable) object;
                    TdlApi.Chat chat = chats.get(update.chatId);
                    synchronized (chat) {
                        chat.isTranslatable = update.isTranslatable;
                    }
                    break;
                }
                case TdlApi.UpdateChatIsMarkedAsUnread.CONSTRUCTOR: {
                    TdlApi.UpdateChatIsMarkedAsUnread update = (TdlApi.UpdateChatIsMarkedAsUnread) object;
                    TdlApi.Chat chat = chats.get(update.chatId);
                    synchronized (chat) {
                        chat.isMarkedAsUnread = update.isMarkedAsUnread;
                    }
                    break;
                }
                case TdlApi.UpdateChatBlockList.CONSTRUCTOR: {
                    TdlApi.UpdateChatBlockList update = (TdlApi.UpdateChatBlockList) object;
                    TdlApi.Chat chat = chats.get(update.chatId);
                    synchronized (chat) {
                        chat.blockList = update.blockList;
                    }
                    break;
                }
                case TdlApi.UpdateChatHasScheduledMessages.CONSTRUCTOR: {
                    TdlApi.UpdateChatHasScheduledMessages update = (TdlApi.UpdateChatHasScheduledMessages) object;
                    TdlApi.Chat chat = chats.get(update.chatId);
                    synchronized (chat) {
                        chat.hasScheduledMessages = update.hasScheduledMessages;
                    }
                    break;
                }

                case TdlApi.UpdateMessageMentionRead.CONSTRUCTOR: {
                    TdlApi.UpdateMessageMentionRead updateChat = (TdlApi.UpdateMessageMentionRead) object;
                    TdlApi.Chat chat = chats.get(updateChat.chatId);
                    synchronized (chat) {
                        chat.unreadMentionCount = updateChat.unreadMentionCount;
                    }
                    break;
                }
                case TdlApi.UpdateMessageUnreadReactions.CONSTRUCTOR: {
                    TdlApi.UpdateMessageUnreadReactions updateChat = (TdlApi.UpdateMessageUnreadReactions) object;
                    TdlApi.Chat chat = chats.get(updateChat.chatId);
                    synchronized (chat) {
                        chat.unreadReactionCount = updateChat.unreadReactionCount;
                    }
                    break;
                }

                case TdlApi.UpdateUserFullInfo.CONSTRUCTOR:
                    TdlApi.UpdateUserFullInfo updateUserFullInfo = (TdlApi.UpdateUserFullInfo) object;
                    usersFullInfo.put(updateUserFullInfo.userId, updateUserFullInfo.userFullInfo);
                    break;
                case TdlApi.UpdateBasicGroupFullInfo.CONSTRUCTOR:
                    TdlApi.UpdateBasicGroupFullInfo updateBasicGroupFullInfo = (TdlApi.UpdateBasicGroupFullInfo) object;
                    basicGroupsFullInfo.put(updateBasicGroupFullInfo.basicGroupId, updateBasicGroupFullInfo.basicGroupFullInfo);
                    break;
                case TdlApi.UpdateSupergroupFullInfo.CONSTRUCTOR:
                    TdlApi.UpdateSupergroupFullInfo updateSupergroupFullInfo = (TdlApi.UpdateSupergroupFullInfo) object;
                    supergroupsFullInfo.put(updateSupergroupFullInfo.supergroupId, updateSupergroupFullInfo.supergroupFullInfo);
                    break;
                default:
                    // print("Unsupported update:" + newLine + object);
            }
        }
    }

    private static class AuthorizationRequestHandler implements Client.ResultHandler {
        @Override
        public void onResult(TdlApi.Object object) {
            switch (object.getConstructor()) {
                case TdlApi.Error.CONSTRUCTOR:
                    System.err.println("Receive an error:" + newLine + object);
                    onAuthorizationStateUpdated(null); // repeat last action
                    break;
                case TdlApi.Ok.CONSTRUCTOR:
                    // result is already received through UpdateAuthorizationState, nothing to do
                    break;
                default:
                    System.err.println("Receive wrong response from TDLib:" + newLine + object);
            }
        }
    }

    private static class LogMessageHandler implements Client.LogMessageHandler {
        @Override
        public void onLogMessage(int verbosityLevel, String message) {
            if (verbosityLevel == 0) {
                onFatalError(message);
                return;
            }
            System.err.println(message);
        }
    }

    private static void onFatalError(String errorMessage) {
        final class ThrowError implements Runnable {
            private final String errorMessage;
            private final AtomicLong errorThrowTime;

            private ThrowError(String errorMessage, AtomicLong errorThrowTime) {
                this.errorMessage = errorMessage;
                this.errorThrowTime = errorThrowTime;
            }

            @Override
            public void run() {
                if (isDatabaseBrokenError(errorMessage) || isDiskFullError(errorMessage) || isDiskError(errorMessage)) {
                    processExternalError();
                    return;
                }

                errorThrowTime.set(System.currentTimeMillis());
                throw new ClientError("TDLib fatal error: " + errorMessage);
            }

            private void processExternalError() {
                errorThrowTime.set(System.currentTimeMillis());
                throw new ExternalClientError("Fatal error: " + errorMessage);
            }

            final class ClientError extends Error {
                private ClientError(String message) {
                    super(message);
                }
            }

            final class ExternalClientError extends Error {
                public ExternalClientError(String message) {
                    super(message);
                }
            }

            private boolean isDatabaseBrokenError(String message) {
                return message.contains("Wrong key or database is corrupted") ||
                        message.contains("SQL logic error or missing database") ||
                        message.contains("database disk image is malformed") ||
                        message.contains("file is encrypted or is not a database") ||
                        message.contains("unsupported file format") ||
                        message.contains("Database was corrupted and deleted during execution and can't be recreated");
            }

            private boolean isDiskFullError(String message) {
                return message.contains("PosixError : No space left on device") ||
                        message.contains("database or disk is full");
            }

            private boolean isDiskError(String message) {
                return message.contains("I/O error") || message.contains("Structure needs cleaning");
            }
        }

        final AtomicLong errorThrowTime = new AtomicLong(Long.MAX_VALUE);
        new Thread(new ThrowError(errorMessage, errorThrowTime), "TDLib fatal error thread").start();

        // wait at least 10 seconds after the error is thrown
        while (errorThrowTime.get() >= System.currentTimeMillis() - 10000) {
            try {
                Thread.sleep(1000 /* milliseconds */);
            } catch (InterruptedException ignore) {
                Thread.currentThread().interrupt();
            }
        }
    }
}

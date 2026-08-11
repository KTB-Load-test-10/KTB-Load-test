import React from 'react';
import { render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import ChatMessages from '../ChatMessages';

vi.mock('../../hooks/useInfiniteScroll', () => ({
  useInfiniteScroll: () => ({ sentinelRef: { current: null } }),
}));

vi.mock('../../hooks/useAutoScroll', () => ({
  useAutoScroll: () => ({
    containerRef: React.createRef(),
    scrollToBottom: vi.fn(),
    isNearBottom: true,
  }),
}));

const { markMessagesAsRead } = vi.hoisted(() => ({ markMessagesAsRead: vi.fn() }));
vi.mock('@/lib/socket/socketClient', () => ({
  default: {
    canSend: () => true,
    markMessagesAsRead,
  },
}));

vi.mock('../SystemMessage', () => ({
  default: ({ msg }) => React.createElement('div', { 'data-testid': 'message' }, msg.content),
}));

vi.mock('../FileMessage', () => ({
  default: ({ msg }) => React.createElement('div', { 'data-testid': 'message' }, msg.content),
}));

vi.mock('../UserMessage', () => ({
  default: ({ msg }) => React.createElement('div', { 'data-testid': 'message' }, msg.content),
}));

describe('ChatMessages', () => {
  let observers;

  beforeEach(() => {
    observers = [];
    globalThis.IntersectionObserver = class {
      constructor(callback) {
        this.callback = callback;
        this.nodes = [];
        observers.push(this);
      }
      observe(node) { this.nodes.push(node); }
      unobserve() {}
      disconnect() {}
      triggerVisible() {
        this.callback(this.nodes.map(target => ({ target, isIntersecting: true })));
      }
    };
  });

  afterEach(() => {
    vi.useRealTimers();
    markMessagesAsRead.mockClear();
    delete globalThis.IntersectionObserver;
  });

  it('renders the already-sorted state without mutating the input array', () => {
    const messages = [
      {
        _id: 'early',
        content: 'early message',
        timestamp: '2026-06-20T11:00:00.000Z',
        sender: { _id: 'other' },
      },
      {
        _id: 'late',
        content: 'late message',
        timestamp: '2026-06-20T12:00:00.000Z',
        sender: { _id: 'other' },
      },
    ];
    const originalOrder = messages.map((message) => message._id);

    render(
      React.createElement(ChatMessages, {
        messages,
        currentUser: { id: 'me' },
        hasMoreMessages: false,
      })
    );

    expect(screen.getAllByTestId('message').map((node) => node.textContent)).toEqual([
      'early message',
      'late message',
    ]);
    expect(messages.map((message) => message._id)).toEqual(originalOrder);
  });

  it('batches visible unread messages into one socket event', () => {
    vi.useFakeTimers();
    render(
      React.createElement(ChatMessages, {
        messages: [
          { _id: 'message-1', content: 'one', timestamp: 1, sender: { _id: 'other' }, readers: [] },
          { _id: 'message-2', content: 'two', timestamp: 2, sender: { _id: 'other' }, readers: [] },
        ],
        currentUser: { id: 'me' },
        hasMoreMessages: false,
      })
    );

    observers.at(-1).triggerVisible();
    vi.advanceTimersByTime(75);

    expect(markMessagesAsRead).toHaveBeenCalledTimes(1);
    expect(new Set(markMessagesAsRead.mock.calls[0][0])).toEqual(
      new Set(['message-1', 'message-2'])
    );
  });

  it('keeps optimized message wrappers discoverable in the rendered DOM', () => {
    render(
      React.createElement(ChatMessages, {
        messages: [
          {
            _id: 'message-1',
            content: 'discoverable message',
            timestamp: '2026-06-20T11:00:00.000Z',
            sender: { _id: 'other' },
          },
        ],
        currentUser: { id: 'me' },
        hasMoreMessages: true,
        loadingMessages: true,
      })
    );

    const message = screen.getByText('discoverable message');
    const optimizedWrapper = message.closest('[style]');

    expect(message).toBeInTheDocument();
    expect(optimizedWrapper).toHaveStyle({
      contentVisibility: 'auto',
      containIntrinsicSize: '1px 96px',
    });
    expect(screen.getByText('이전 메시지를 불러오는 중...')).toBeInTheDocument();
  });
});

import React from 'react';
import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import RoomsTable from '../RoomsTable';
import { CONNECTION_STATUS } from '../useServerConnection';

vi.mock('@/hooks/useInfiniteScroll', () => ({
  default: () => ({ sentinelRef: { current: null } }),
}));

describe('RoomsTable', () => {
  it('shows participantsCount instead of depending on the participants array', () => {
    render(
      <RoomsTable
        rooms={[{
          _id: 'room-1',
          name: '경량 목록 방',
          hasPassword: false,
          participantsCount: 2,
          participants: [{}, {}, {}, {}, {}],
          recentMessageCount: 0,
          createdAt: '2026-08-11T00:00:00.000Z',
        }]}
        connectionStatus={CONNECTION_STATUS.CONNECTED}
        onJoinRoom={vi.fn()}
        hasMore={false}
        isLoadingMore={false}
        onLoadMore={vi.fn()}
      />
    );

    expect(screen.getByText('2')).toBeTruthy();
    expect(screen.queryByText('5')).toBeNull();
  });
});

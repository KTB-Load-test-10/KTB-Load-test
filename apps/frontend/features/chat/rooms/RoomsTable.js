import React, { useMemo, useRef } from 'react';
import { LockIcon, GroupIcon } from '@vapor-ui/icons';
import { Button, Text, VStack, HStack } from '@vapor-ui/core';
import * as Table from '@/components/Table';
import { CONNECTION_STATUS } from './useServerConnection';
import useInfiniteScroll from '@/hooks/useInfiniteScroll';

const RoomsTable = ({ rooms, connectionStatus, onJoinRoom, hasMore, isLoadingMore, onLoadMore }) => {
  const scrollRootRef = useRef(null);
  const observerOptions = useMemo(() => ({ root: scrollRootRef.current, rootMargin: '120px', threshold: 0.1 }), [rooms.length]);
  const { sentinelRef } = useInfiniteScroll(onLoadMore, hasMore, isLoadingMore, observerOptions);
  if (!rooms || rooms.length === 0) return null;

  return (
    <div
      className="chat-rooms-table"
      ref={scrollRootRef}
      style={{
        height: '430px',
        overflowY: 'auto',
        position: 'relative',
        borderRadius: '0.5rem',
        backgroundColor: 'var(--background-normal)',
        border: '1px solid var(--border-color)',
        scrollBehavior: 'smooth',
        WebkitOverflowScrolling: 'touch',
      }}
    >
      <Table.Root style={{ width: '100%' }}>
        <Table.ColumnGroup>
          <Table.Column style={{ width: '40%' }} />
          <Table.Column style={{ width: '12%' }} />
          <Table.Column style={{ width: '12%' }} />
          <Table.Column style={{ width: '21%' }} />
          <Table.Column style={{ width: '15%' }} />
        </Table.ColumnGroup>

        <Table.Header>
          <Table.Row>
            <Table.Heading>채팅방</Table.Heading>
            <Table.Heading>참여자</Table.Heading>
            <Table.Heading>최근 메시지</Table.Heading>
            <Table.Heading>생성일</Table.Heading>
            <Table.Heading>액션</Table.Heading>
          </Table.Row>
        </Table.Header>

        <Table.Body>
          {rooms.map((room) => (
            <Table.Row key={room._id}>
              <Table.Cell>
                <VStack $css={{ gap: '$050', alignItems: 'flex-start' }}>
                  <Text style={{ fontWeight: 500 }}>{room.name}</Text>
                  {room.hasPassword && (
                    <HStack $css={{ gap: '$050', alignItems: 'center', color: '$warning-100' }}>
                      <LockIcon size={16} />
                      <Text typography="body3" foreground="warning-100">
                        비밀번호 필요
                      </Text>
                    </HStack>
                  )}
                </VStack>
              </Table.Cell>
              <Table.Cell>
                <HStack $css={{ gap: '$050', alignItems: 'center' }}>
                  <GroupIcon />
                  <Text typography="body2">{room.participants?.length || 0}</Text>
                </HStack>
              </Table.Cell>
              <Table.Cell>
                {room.recentMessageCount > 0 ? room.recentMessageCount : '-'}
              </Table.Cell>
              <Table.Cell>
                <time dateTime={new Date(room.createdAt).toISOString()}>
                  {new Date(room.createdAt).toLocaleString('ko-KR', {
                    year: 'numeric',
                    month: '2-digit',
                    day: '2-digit',
                    hour: '2-digit',
                    minute: '2-digit',
                  })}
                </time>
              </Table.Cell>
              <Table.Cell>
                <Button
                  colorPalette="primary"
                  size="md"
                  onClick={() => onJoinRoom(room._id)}
                  disabled={connectionStatus !== CONNECTION_STATUS.CONNECTED}
                  data-testid="join-chat-room-button"
                >
                  입장
                </Button>
              </Table.Cell>
            </Table.Row>
          ))}
          {hasMore && (
            <Table.Row>
              <Table.Cell colSpan={5}>
                <div ref={sentinelRef} style={{ height: '1px', textAlign: 'center' }}>
                  {isLoadingMore ? '채팅방을 불러오는 중…' : ''}
                </div>
              </Table.Cell>
            </Table.Row>
          )}
        </Table.Body>
      </Table.Root>
    </div>
  );
};

export default RoomsTable;

import React from 'react';
import { render, waitFor, fireEvent } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import ChatInput from '../ChatInput';

// lazy import 자체는 유지하면서 emoji-mart의 테스트 중 CDN 접근은 차단한다.
vi.mock('../EmojiPicker', () => ({
  default: () => <em-emoji-picker />,
}));

describe('ChatInput', () => {
  it('renders the lazy emoji picker under React 19', async () => {
    const { container, getByLabelText } = render(
      <ChatInput
        fileInputRef={{ current: null }}
        room={{ participants: [] }}
      />
    );

    fireEvent.click(getByLabelText('이모티콘'));

    await waitFor(() => {
      expect(container.querySelector('em-emoji-picker')).toBeInTheDocument();
    });
  });
});

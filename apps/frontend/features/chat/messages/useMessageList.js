export const deriveUniqueSortedMessages = (
  currentMessages,
  incomingMessages,
  processedMessageIds
) => {
  if (!Array.isArray(incomingMessages)) {
    throw new Error('Invalid messages format');
  }

  const processedSnapshot = new Set(processedMessageIds);
  const nextProcessedMessageIds = new Set(processedMessageIds);
  const newMessages = incomingMessages.filter((message) => {
    if (!message._id) {
      return false;
    }

    if (processedSnapshot.has(message._id)) {
      return false;
    }

    processedSnapshot.add(message._id);
    nextProcessedMessageIds.add(message._id);
    return true;
  });

  const compareMessageOrder = (a, b) => {
    const timestampDifference =
      new Date(a.timestamp || 0) - new Date(b.timestamp || 0);
    if (timestampDifference !== 0) {
      return timestampDifference;
    }

    return String(a._id || '').localeCompare(String(b._id || ''));
  };
  newMessages.sort(compareMessageOrder);

  // 중요: currentMessages는 이미 정렬됐다는 불변식을 이용해 O(n log n) 재정렬을 피한다.
  const merged = [];
  let currentIndex = 0;
  let incomingIndex = 0;
  while (currentIndex < currentMessages.length && incomingIndex < newMessages.length) {
    if (compareMessageOrder(currentMessages[currentIndex], newMessages[incomingIndex]) <= 0) {
      merged.push(currentMessages[currentIndex++]);
    } else {
      merged.push(newMessages[incomingIndex++]);
    }
  }
  merged.push(...currentMessages.slice(currentIndex), ...newMessages.slice(incomingIndex));

  return {
    messages: merged,
    processedMessageIds: nextProcessedMessageIds,
  };
};

export const mergeUniqueSortedMessages = (
  currentMessages,
  incomingMessages,
  processedMessageIds
) => {
  return deriveUniqueSortedMessages(
    currentMessages,
    incomingMessages,
    processedMessageIds
  ).messages;
};

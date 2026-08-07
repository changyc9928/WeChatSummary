import { useEffect, useState } from 'react';

export default function useTimeWindow(uuidInput) {
  const [selectedStartTime, setSelectedStartTime] = useState('');
  const [selectedEndTime, setSelectedEndTime] = useState('');

  useEffect(() => {
    setSelectedStartTime('');
    setSelectedEndTime('');
  }, [uuidInput]);

  return {
    selectedStartTime,
    setSelectedStartTime,
    selectedEndTime,
    setSelectedEndTime
  };
}
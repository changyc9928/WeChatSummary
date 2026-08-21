package sqlite

import "math"

func float64FromBits(u uint64) float64 { return math.Float64frombits(u) }
